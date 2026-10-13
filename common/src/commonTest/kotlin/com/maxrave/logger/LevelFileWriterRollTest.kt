package com.maxrave.logger

import co.touchlab.kermit.Severity
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * 滚动命名回归(CR 五复核:首版 rollFiles 拼出 `app.log-1.log`,而 Logger.logFiles 读的是
 * `app-1.log`——轮转内容日志页/导出/删除全找不到)。用极小 rollOnSize 强制滚动,断言
 * 落盘文件名与 logFiles() 完全一套。
 */
class LevelFileWriterRollTest {
    @Test
    fun rolledFilesUseLogFilesNaming() {
        val dir = Path("levelwriter-test-${Random.nextInt(1_000_000)}") // cwd 相对,三端测试源集均可编译运行
        SystemFileSystem.createDirectories(dir)
        val writer =
            LevelFileWriter(
                directory = dir,
                name = "app",
                rollOnSize = 200,
                maxLogFiles = 3,
                minSeverity = Severity.Info,
            )
        try {
            // 每行约 60B,50 行必跨多次滚动
            repeat(50) { writer.log(Severity.Warn, "message $it line", "TagTest", null) }
            // 写入协程异步消费,轮询等待滚动文件出现(上限 5s)
            val deadline = Clock.System.now().toEpochMilliseconds() + 5_000
            while (Clock.System.now().toEpochMilliseconds() < deadline &&
                !SystemFileSystem.exists(Path(dir, "app-1.log"))
            ) {
                runBlocking { delay(50) }
            }

            // logFiles() 的命名契约(与 Logger.logFiles 一致):live=`app.log`,历史=`app-N.log`
            val expected = listOf("app-1.log", "app-2.log", "app.log")
            expected.forEach {
                assertTrue(SystemFileSystem.exists(Path(dir, it)), "应存在 $it")
            }
            assertFalse(SystemFileSystem.exists(Path(dir, "app.log-1.log")), "不得出现双后缀错误命名")
            // 目录里除了这三个命名不得有其它残留
            val names = SystemFileSystem.list(dir).map { it.name }.sorted()
            assertEquals(expected, names, "目录文件应恰为 kermit 命名集")
        } finally {
            writer.shutdown()
            SystemFileSystem.list(dir).forEach { runCatching { SystemFileSystem.delete(it, mustExist = false) } }
            runCatching { SystemFileSystem.delete(dir, mustExist = false) }
        }
    }
}

/** 旧版双后缀轮转文件(`app.log-1.log`)必须被 clearLogFiles 一并删掉(CR 六复核) */
class LoggerClearLegacyFilesTest {
    @Test
    fun clearRemovesLegacyDoubleSuffixFiles() {
        val dir = Path("logger-clear-test-${Random.nextInt(1_000_000)}")
        SystemFileSystem.createDirectories(dir)
        // 模拟 bug 期现场:正常命名 + 双后缀残留并存
        listOf("app.log", "app-1.log", "app.log-1.log", "app.log-2.log", "error.log", "error-1.log", "error.log-1.log")
            .forEach { SystemFileSystem.sink(Path(dir, it)).close() }
        try {
            Logger.enableFileLogging(dir.toString())
            Logger.clearLogFiles()
            SystemFileSystem.list(dir).forEach {
                assertFalse(it.name.contains(".log-"), "双后缀残留未清: " + it.name)
            }
            Logger.logFiles(Logger.APP_LOG).forEach { path ->
                assertFalse(SystemFileSystem.exists(Path(path)), "正常命名未清: " + path)
            }
        } finally {
            SystemFileSystem.list(dir).forEach { runCatching { SystemFileSystem.delete(it, mustExist = false) } }
            runCatching { SystemFileSystem.delete(dir, mustExist = false) }
        }
    }
}
