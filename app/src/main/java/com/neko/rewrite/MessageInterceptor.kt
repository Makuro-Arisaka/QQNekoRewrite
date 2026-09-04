package com.neko.rewrite

import android.os.Handler
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Member
import java.lang.reflect.Method
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * 消息拦截器 — 使用 hookAllMethods 适配不同 QQ 版本
 *
 * ## 改写时序：异步接管模式
 *
 * 同步在 Hook 回调里调 AI 会阻塞 QQ 的发送线程（最长 20 秒），因此默认改为：
 *
 * ```
 * beforeHookedMethod
 *   ├─ 找到待改写的文本元素
 *   ├─ param.result = null        ← 阻断原方法，发送线程立即返回
 *   └─ 提交异步任务
 *        ├─ AI 改写（受 rewriteTimeoutMs 熔断）
 *        ├─ 写回 textElement.content
 *        └─ finally: invokeOriginalMethod 重放发送   ← 成败都必须发
 * ```
 *
 * `invokeOriginalMethod` 直接调用原始实现、不触发 Hook，因此无需防重入标记。
 *
 * 四道兜底，任何一环出问题都不会丢消息：
 * 1. 有返回值的方法不接管（阻断后调用方拿到 null 会 NPE）→ 退回同步
 * 2. 队列积压超过阈值 → 退回同步
 * 3. 队列满（RejectedExecutionException）→ 当前线程立即同步执行
 * 4. 重放抛异常 → 切主线程再试一次
 */
object MessageInterceptor {

    private const val KELEMTYPETEXT = 1

    /** 等待队列上限 */
    private const val MAX_PENDING = 8

    /** 队列中已积压这么多任务时，新消息退回同步路径，避免延迟累积 */
    private const val DEGRADE_THRESHOLD = 2

    /**
     * 单线程串行执行 —— 保证多条消息严格按发送顺序重放，不会乱序。
     */
    private val rewriteQueue = ThreadPoolExecutor(
        1, 1,
        60L, TimeUnit.SECONDS,
        LinkedBlockingQueue(MAX_PENDING),
        { runnable -> Thread(runnable, "NekoRewrite-Async").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy()
    )

    @Volatile
    private var qqContext: android.content.Context? = null

    /** 待改写的目标文本 */
    private data class TextTarget(val textElement: Any, val original: String)

    /**
     * 设置 QQ 进程的 Context（用于显示 Toast 与主线程重试）
     */
    fun setContext(context: android.content.Context) {
        qqContext = context
    }

    fun install(classLoader: ClassLoader) {
        // 策略：使用 hookAllMethods 钩住所有同名的 sendMsg/addSendMsg 方法，不依赖精确签名
        var hooked = tryHookAllMethods(
            classLoader,
            "com.tencent.qqnt.kernel.api.impl.MsgService",
            "sendMsg"
        )
        if (hooked) {
            XposedBridge.log("[NekoRewrite] ✅ MsgService.sendMsg(*) 已 Hook（所有重载）")
            LogRecorder.success("Hook", "MsgService.sendMsg(*) 所有重载")
        }

        if (!hooked) {
            // 尝试 IMsgService 接口
            hooked = tryHookAllMethods(
                classLoader,
                "com.tencent.qqnt.kernel.api.IMsgService",
                "sendMsg"
            )
            if (hooked) {
                XposedBridge.log("[NekoRewrite] ✅ IMsgService.sendMsg(*) 已 Hook")
                LogRecorder.success("Hook", "IMsgService.sendMsg(*)")
            }
        }

        if (!hooked) {
            // 尝试 addSendMsg on CppProxy
            hooked = tryHookAllMethods(
                classLoader,
                "com.tencent.qqnt.kernel.nativeinterface.IKernelMsgService\$CppProxy",
                "addSendMsg"
            )
            if (hooked) {
                XposedBridge.log("[NekoRewrite] ✅ CppProxy.addSendMsg(*) 已 Hook")
                LogRecorder.success("Hook", "CppProxy.addSendMsg(*)")
            }
        }

        if (!hooked) {
            XposedBridge.log("[NekoRewrite] ❌ 所有 Hook 点均失败！")
            LogRecorder.error("Hook", "所有 Hook 点均失败")
        }
    }

    /**
     * Hook 指定类的所有同名方法（不限制参数签名）
     */
    private fun tryHookAllMethods(classLoader: ClassLoader, className: String, methodName: String): Boolean {
        return try {
            val clazz = XposedHelpers.findClass(className, classLoader)
            XposedBridge.hookAllMethods(clazz, methodName, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    onMessageHook(param, methodName)
                }
            })
            true
        } catch (e: Throwable) {
            XposedBridge.log("[NekoRewrite] ⚠️ $className.$methodName Hook 失败: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    /**
     * 统一消息处理回调
     */
    private fun onMessageHook(param: XC_MethodHook.MethodHookParam, hookName: String) {
        if (!ConfigManager.config.enabled) return

        // API Key 为空时跳过（避免每条消息都触发 AI 调用）
        if (!AiRewriteEngine.canRewrite()) {
            return
        }

        try {
            // 提取联系人信息（用于白名单/黑名单过滤）
            val contact = ContactFilter.extractContact(param.args)

            // 过滤判定：命中白名单/黑名单规则则跳过改写
            if (contact.isValid && ContactFilter.shouldSkip(contact.peerUid)) {
                XposedBridge.log("[NekoRewrite] 🚫 联系人已过滤 (${contact.typeLabel}): ${contact.peerUid}")
                return
            }

            // 找到第一个可改写的文本元素
            val target = findFirstTextElement(param.args) ?: return

            // 记录联系人信息（仅记录有效联系人，供设置页参考）
            if (contact.isValid) {
                ContactFilter.logContact(contact.peerUid!!, contact.chatType)
            }
            logIntercept(target.original, hookName, contact)

            if (canTakeOverAsync(param)) {
                takeOverAsync(param, target, hookName, contact)
            } else {
                rewriteInPlace(target)
            }
        } catch (e: Throwable) {
            XposedBridge.log("[NekoRewrite] onMessageHook error: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    // region 异步接管

    /**
     * 判断能否安全地接管这个方法调用。
     * 有任何不放心的因素都退回同步路径 —— 阻断一个不该方法会让消息彻底发不出去。
     */
    private fun canTakeOverAsync(param: XC_MethodHook.MethodHookParam): Boolean {
        if (!ConfigManager.config.asyncRewrite) return false

        val method = param.method as? Method ?: return false
        if (method.returnType != Void.TYPE) {
            // 有返回值的方法被阻断后，调用方会拿到 null，可能 NPE
            XposedBridge.log("[NekoRewrite] ℹ️ ${method.name} 返回 ${method.returnType.simpleName}，退回同步改写")
            return false
        }

        if (rewriteQueue.queue.size >= DEGRADE_THRESHOLD) {
            XposedBridge.log("[NekoRewrite] ⚠️ 改写队列积压 ${rewriteQueue.queue.size} 条，退回同步改写")
            return false
        }
        return true
    }

    private fun takeOverAsync(
        param: XC_MethodHook.MethodHookParam,
        target: TextTarget,
        hookName: String,
        contact: ContactFilter.ContactInfo
    ) {
        // 阻断原方法：setResult(null) 会同时置 returnEarly=true（见 Xposed API MethodHookParam），
        // 原方法被跳过、发送线程立即返回，消息由下方异步任务稍后重放。
        param.setResult(null)

        // param 在 beforeHookedMethod 返回后不可再依赖，先把需要的数据拷出来。
        // args 只做浅拷贝（数组本身），元素引用保持不变，写回仍作用于原始对象。
        val method: Member = param.method
        val receiver: Any? = param.thisObject
        val args: Array<Any?> = param.args.copyOf()

        val task = Runnable {
            try {
                rewriteAndApply(target, "异步")
            } finally {
                // 无论改写成败，都必须把消息发出去
                replayOriginal(method, receiver, args, hookName, contact)
            }
        }

        try {
            rewriteQueue.execute(task)
            XposedBridge.log("[NekoRewrite] ⏳ 已接管发送，等待 AI 改写 [$hookName]")
        } catch (e: RejectedExecutionException) {
            // 队列满：在当前线程立刻跑完，等价于同步路径，绝不丢消息
            XposedBridge.log("[NekoRewrite] ⚠️ 改写队列已满，立即同步处理")
            LogRecorder.warn("Hook", "改写队列已满，降级为同步发送")
            task.run()
        }
    }

    /**
     * 重放原始发送调用。
     * invokeOriginalMethod 直接调用方法的原始实现，不会再次触发 Hook。
     */
    private fun replayOriginal(
        method: Member,
        receiver: Any?,
        args: Array<Any?>,
        hookName: String,
        contact: ContactFilter.ContactInfo
    ) {
        try {
            XposedBridge.invokeOriginalMethod(method, receiver, args)
            XposedBridge.log("[NekoRewrite] 📤 已重放发送 [$hookName]")
            LogRecorder.msg("Hook", "已重放发送${if (contact.isValid) " peer=${contact.peerUid}" else ""}")
        } catch (t: Throwable) {
            XposedBridge.log("[NekoRewrite] ❌ 重放发送失败 [$hookName]: ${t.javaClass.simpleName}: ${t.message}")
            LogRecorder.error("Hook", "重放发送失败: ${t.message}")
            retryOnMainThread(method, receiver, args, hookName)
        }
    }

    /**
     * 最后兜底：某些实现可能要求在特定线程调用，切到主线程再试一次
     */
    private fun retryOnMainThread(method: Member, receiver: Any?, args: Array<Any?>, hookName: String) {
        val context = qqContext
        if (context == null) {
            LogRecorder.error("Hook", "无法重试：QQ Context 未就绪，该条消息可能未发出")
            return
        }
        try {
            Handler(context.mainLooper).post {
                try {
                    XposedBridge.invokeOriginalMethod(method, receiver, args)
                    XposedBridge.log("[NekoRewrite] 📤 主线程重放成功 [$hookName]")
                } catch (t: Throwable) {
                    LogRecorder.error("Hook", "主线程重放仍失败: ${t.message}")
                }
            }
        } catch (t: Throwable) {
            LogRecorder.error("Hook", "主线程重试调度失败: ${t.message}")
        }
    }

    // endregion

    // region 改写执行

    /** 同步路径：就地改写，原方法随后照常执行 */
    private fun rewriteInPlace(target: TextTarget) {
        rewriteAndApply(target, "同步")
    }

    /** 执行一次改写并把结果写回 MsgElement，返回最终文本 */
    private fun rewriteAndApply(target: TextTarget, mode: String): String {
        val start = System.currentTimeMillis()
        val rewritten = safeRewrite(target.original)
        val elapsed = System.currentTimeMillis() - start

        XposedHelpers.setObjectField(target.textElement, "content", rewritten)

        if (rewritten == target.original) {
            XposedBridge.log("[NekoRewrite] ⚠️ AI 改写失败，使用原文 (${elapsed}ms) [$mode]")
            if (ConfigManager.config.showToast) {
                showOriginalToast(target.original)
            }
        } else {
            XposedBridge.log("[NekoRewrite] 🤖 改写成功 (${elapsed}ms) [$mode]: ${rewritten.take(60)}")
            if (ConfigManager.config.showToast) {
                showRewriteToast(target.original, rewritten)
            }
        }
        return rewritten
    }

    private fun safeRewrite(original: String): String = try {
        AiRewriteEngine.rewrite(original, ConfigManager.config.rewriteTimeoutMs)
    } catch (t: Throwable) {
        XposedBridge.log("[NekoRewrite] ❌ 改写异常: ${t.javaClass.simpleName}: ${t.message}")
        LogRecorder.error("Hook", "改写异常: ${t.message}")
        original
    }

    // endregion

    // region 参数解析

    /**
     * 从方法参数中找出第一个文本元素。
     * 遍历所有 ArrayList 参数，靠 elementType 字段识别 MsgElement，不依赖参数位置。
     */
    private fun findFirstTextElement(args: Array<Any?>): TextTarget? {
        for (arg in args) {
            if (arg !is ArrayList<*>) continue
            for (element in arg) {
                if (element == null) continue
                try {
                    if (XposedHelpers.getIntField(element, "elementType") != KELEMTYPETEXT) continue
                    val textElement = XposedHelpers.getObjectField(element, "textElement") ?: continue
                    val content = XposedHelpers.getObjectField(textElement, "content") as? String
                    if (!content.isNullOrBlank()) {
                        return TextTarget(textElement, content)
                    }
                } catch (_: Throwable) {
                    // 不是 MsgElement，继续查找
                }
            }
        }
        return null
    }

    private fun logIntercept(original: String, hookName: String, contact: ContactFilter.ContactInfo) {
        val peerInfo = if (contact.isValid) " (${contact.typeLabel}:${contact.peerUid})" else ""
        XposedBridge.log("[NekoRewrite] 💬 拦截消息 [$hookName]$peerInfo: ${original.take(50)}")
        LogRecorder.msg("Hook", "拦截${if (contact.isValid) " peer=${contact.peerUid}" else ""}: ${original.take(50)}")
    }

    /**
     * 显示改写结果 Toast（在 QQ 主线程执行）
     */
    private fun showRewriteToast(original: String, rewritten: String) {
        val context = qqContext ?: return
        try {
            val shortOriginal = original.take(20) + if (original.length > 20) "…" else ""
            val shortRewritten = rewritten.take(30) + if (rewritten.length > 30) "…" else ""
            val msg = "🐱 已改写: $shortOriginal → $shortRewritten"
            Handler(context.mainLooper).post {
                try {
                    android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                } catch (_: Throwable) { }
            }
        } catch (e: Exception) {
            XposedBridge.log("[NekoRewrite] Toast 显示失败: ${e.message}")
        }
    }

    /**
     * 显示「以原文发送」Toast（改写未生效 / 回退原文时）。
     * 与改写成功 Toast 共用 showToast 开关，在 QQ 主线程执行。
     */
    private fun showOriginalToast(original: String) {
        val context = qqContext ?: return
        try {
            val shortOriginal = original.take(30) + if (original.length > 30) "…" else ""
            val msg = "🐱 未改写，已发送原文: $shortOriginal"
            Handler(context.mainLooper).post {
                try {
                    android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                } catch (_: Throwable) { }
            }
        } catch (e: Exception) {
            XposedBridge.log("[NekoRewrite] Toast 显示失败: ${e.message}")
        }
    }

    // endregion
}
