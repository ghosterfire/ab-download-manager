/*
 * This Kotlin source file was generated by the Gradle 'init' task.
 */
package com.abdownloadmanager.desktop

import com.abdownloadmanager.desktop.di.Di
import com.abdownloadmanager.desktop.ui.Ui
import com.abdownloadmanager.desktop.utils.*
import com.abdownloadmanager.desktop.utils.singleInstance.*
import com.abdownloadmanager.integration.Integration
import ir.amirab.util.platform.Platform
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toOkioPath
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.system.exitProcess


class App : AutoCloseable,
    KoinComponent {
    private val downloadSystem: DownloadSystem by inject()
    private val integration: Integration by inject()
    //TODO Setup Native Messaging Feature
    //private val browserNativeMessaging: NativeMessaging by inject()
    fun start(
        appArguments: AppArguments,
        singleInstanceServerHandler: MutableSingleInstanceServerHandler,
        globalAppExceptionHandler: GlobalAppExceptionHandler,
    ) {
        try {
            runBlocking {
                //make sure to not get any dependency until boot the DI Container
                Di.boot()
                integration.boot()
                downloadSystem.boot()
                //TODO Setup Native Messaging Feature
                //waiting for compose kmp to add multi launcher to nativeDistributions,the PR is already exists but not merger
                //or maybe I should use a custom solution
                //browserNativeMessaging.boot()
                SingleInstanceServerInitializer.boot(singleInstanceServerHandler)
                Ui.boot(appArguments, globalAppExceptionHandler)
            }
        } catch (e: Exception) {
            globalAppExceptionHandler.onProcessIsUseless()
            throw e
        }
    }

    override fun close() {
        //nothing yet!
    }
}


fun main(args: Array<String>) {
    try {
        AppArguments.init(args)
        AppProperties.boot()
        val appArguments = AppArguments.get()
        val singleInstance = SingleInstanceUtil(AppInfo.configDir.toOkioPath())
        if (appArguments.startIfNotStarted && !AppInfo.isInIDE()) {
            startAndWaitForRunIfNotRunning(singleInstance)
        }
        if (appArguments.getIntegrationPort) {
            dispatchIntegrationPortAndExit(singleInstance)
        }
        //going to start main app
        defaultApp(
            singleInstance = singleInstance,
            appArguments = appArguments,
        )
    } catch (e:Throwable){
        System.err.println("Fail to start the ${AppInfo.name} app because:")
        e.printStackTrace()
        exitProcess(-1)
    }
}

private fun startAppInAnotherProcess() {
    val exeFile = requireNotNull(AppInfo.exeFile)
    val cmd = listOf(
        exeFile,
        AppArguments.Args.BACKGROUND
    ).joinToString(" ").also {
//        println("executing $it")
    }
    Runtime.getRuntime().exec(cmd)
}

private fun dispatchIntegrationPortAndExit(singleInstance: SingleInstanceUtil): Nothing {
    val port =
            singleInstance.sendToInstance(Commands.getIntegrationPort)
                .orElse { IntegrationPortBroadcaster.INTEGRATION_UNKNOWN }
    print(port)
    exitProcess(0)
}

private fun startAndWaitForRunIfNotRunning(
    singleInstance: SingleInstanceUtil,
    howMuchWait: Long = 10_000,
    initialDelay: Long = 0,
    eachTimeDelay: Long = 500L,
) {
    val deadLine = System.currentTimeMillis() + howMuchWait
    if (initialDelay > 0) {
        Thread.sleep(initialDelay)
    }
    var firstLoop = true
    while (true) {
        val isReady: Boolean = singleInstance
            .sendToInstance(Commands.isReady)
            .orElse {
//                println("or else $it")
                false
            }
//        println("isReady: $isReady")
        if (isReady) {
            return
        }
        if (firstLoop) {
            startAppInAnotherProcess()
//            println("send start signal")
        }
        if (System.currentTimeMillis() >= deadLine) {
//            println("dead line reached")
            //deadline reached exiting now
            exitProcess(1)
        }
        Thread.sleep(eachTimeDelay)
        firstLoop = false
    }
}

private fun defaultApp(
    appArguments: AppArguments,
    singleInstance: SingleInstanceUtil,
) {
    val singleInstanceServerHandler by lazy { MutableSingleInstanceServerHandler() }
    try {
        singleInstance.lockInstance { singleInstanceServerHandler }
    } catch (e: AnotherInstanceIsRunning) {
        println("instance already running")
        singleInstance.sendToInstance(Commands.showUserThatAppIsRunning)
        return
    }
    if (AppInfo.isInIDE()) {
        println("app version ${AppVersion.get()} is started")
        println("it seems we are in ide")
    }
    if (System.getProperty("skiko.renderApi")==null){
        if (Platform.getCurrentPlatform()==Platform.Desktop.Windows){
            // At the moment default render api have some problems on windows!
            // - when I resize a window, the contents of the window will be stretched
            // - sometimes when I close a window, the window flashes on exiting
            // it seems OPENGL does not have these problems
            System.setProperty("skiko.renderApi","OPENGL")
        }
    }
    val globalExceptionHandler = createAndSetGlobalExceptionHandler()
    App().use {
        it.start(
            appArguments = appArguments,
            globalAppExceptionHandler = globalExceptionHandler,
            singleInstanceServerHandler = singleInstanceServerHandler,
        )
    }
}
