package com.example.proxyactivity

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Message
import java.lang.reflect.Proxy


private const val TROJAN_INTENT = "TROJAN_INTENT"
private const val IS_TROJAN = "IS_TROJAN_INTENT"
private const val EXECUTE_TRANSACTION = 159

object ActivityProxy {

    fun init() {
        registerProxyToActivityStart()
        addCallbackToMainHandler()
    }

    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    private fun registerProxyToActivityStart() {
        val taskManager = Class.forName("android.app.ActivityTaskManager")
        val taskManagerField = taskManager.getDeclaredField("IActivityTaskManagerSingleton")
        taskManagerField.isAccessible = true
        val taskManagerSingleton = taskManagerField.get(null)

        val singleton = Class.forName("android.util.Singleton")
        val singletonInstanceField = singleton.getDeclaredField("mInstance")
        // receive via get method because singleton initialize lazily and may be not initialized
        val singletonGetMethod = singleton.getDeclaredMethod("get")
        singletonGetMethod.isAccessible = true
        val taskManagerInstance = singletonGetMethod.invoke(taskManagerSingleton)

        singletonInstanceField.isAccessible = true
        singletonInstanceField.set(taskManagerSingleton, createStartActivityProxy(taskManagerInstance))
    }

    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    private fun addCallbackToMainHandler() {
        val activityThreadClass = Class.forName("android.app.ActivityThread")
        val activityThreadField = activityThreadClass.getDeclaredField("sCurrentActivityThread")
        activityThreadField.isAccessible = true
        val activityThreadInstance = activityThreadField.get(null)

        val mainHandlerField = activityThreadClass.getDeclaredField("mH")
        mainHandlerField.isAccessible = true
        val mainHandler = mainHandlerField.get(activityThreadInstance) as Handler
        val handlerCallbackField = Handler::class.java.getDeclaredField("mCallback")
        handlerCallbackField.isAccessible = true
        handlerCallbackField.set(mainHandler, object : Handler.Callback {
            override fun handleMessage(msg: Message): Boolean {
                if (msg.what == EXECUTE_TRANSACTION) {
                    val activityCallbacksField =
                        msg.obj::class.java.getDeclaredField("mActivityCallbacks")
                    activityCallbacksField.isAccessible = true
                    val activityCallbacks = activityCallbacksField.get(msg.obj) as List<*>

                    for (callback in activityCallbacks) {
                        callback ?: continue
                        if (callback.javaClass.name != "android.app.servertransaction.LaunchActivityItem") continue
                        val intentField = callback.javaClass.getDeclaredField("mIntent")
                        intentField.isAccessible = true
                        val proxyIntent = intentField.get(callback) as Intent
                        val trojanIntent = proxyIntent.getParcelableExtra<Intent>(TROJAN_INTENT)
                        if (trojanIntent != null) {
                            intentField.set(callback, trojanIntent)
                        }
                    }
                }
                return false
            }
        })

    }


    @SuppressLint("PrivateApi")
    private fun createStartActivityProxy(taskManagerInstance: Any?): Any = Proxy.newProxyInstance(
        Thread.currentThread().contextClassLoader,
        arrayOf(Class.forName("android.app.IActivityTaskManager"))
    ) { proxy, method, args ->

        if (method.name == "startActivity") {
            val targetIndex = args.indexOfFirst { it is Intent }
            val targetIntent = args[targetIndex] as Intent

            if (targetIntent.hasExtra(IS_TROJAN)) {
                val proxyIntent = Intent().apply {
                    component = ComponentName(
                        "com.example.proxyactivity",
                        "com.example.proxyactivity.ProxyActivity"
                    )
                }

                proxyIntent.putExtra(TROJAN_INTENT, targetIntent)
                args[targetIndex] = proxyIntent
            }
        }
        method.invoke(taskManagerInstance, *args)
    }
}

fun Context.startUndeclaredActivity(intent: Intent, options: Bundle? = null) {
    intent.putExtra(IS_TROJAN, true)
    startActivity(intent, options)
}