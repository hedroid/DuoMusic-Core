package com.maxrave.logger

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Message
import co.touchlab.kermit.MessageStringFormatter
import co.touchlab.kermit.Severity
import co.touchlab.kermit.Tag
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlinx.io.IOException
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.concurrent.Volatile

object Logger {
    private val logger = Logger

    // Tags suppressed at all log levels. Add a tag here to silence its logs globally.
    private val mutedTags =
        setOf(
            "DiscordWebSocket",
        )

    private fun isMuted(tag: String): Boolean = tag in mutedTags

    const val APP_LOG = "app"
    const val ERROR_LOG = "error"
    private const val APP_LOG_FILES = 3
    private const val ERROR_LOG_FILES = 2

    /** The folder [enableFileLogging] writes to; null while the log only reaches Logcat/console. */
    var logDirectory: String? = null
        private set

    /**
     * 文件记录总开关(2026-10-13):App log 页的暂停按钮翻它——false 时两个文件写入器在
     * [LevelFileWriter.log] 本体拦截,日志不再落盘;**Logcat/console 镜像照常**(CR P2:
     * 早前把门控放在 i/w/e 漏斗层,暂停连控制台诊断一起停了)。暂停期间的内容不补录。
     */
    @Volatile
    var fileLoggingEnabled: Boolean = true

    /** 当前挂在 kermit 上的文件写入器;[clearLogFiles] 会整体退役重建 */
    private var fileWriters: List<LevelFileWriter> = emptyList()

    /**
     * 真删日志文件(2026-10-13 用户反馈"点删除下次进来日志还在"):旧实现只是把页面
     * 过滤器拨到当前时刻(clearedAt),文件原样,重进全回来。删除本会话全部 app/error
     * 滚动文件,**并退役旧写入器、重建两个新写入器**(CR P2:只删文件不重建,旧句柄指向
     * 被删 inode,本会话新日志全部不可见,"重新开始"也救不回)——新日志立刻落进新文件。
     */
    fun clearLogFiles() {
        val directory = logDirectory ?: return
        fileWriters.forEach { it.shutdown() }
        logFiles(APP_LOG).forEach { path -> runCatching { SystemFileSystem.delete(Path(path), mustExist = false) } }
        logFiles(ERROR_LOG).forEach { path -> runCatching { SystemFileSystem.delete(Path(path), mustExist = false) } }
        // 旧版(轮转命名 bug 期)误拼的双后缀轮转文件:`app.log-1.log` 这类不在 logFiles()
        // 列表里,只清列表会留僵尸(CR 六复核);一并删除,mustExist=false 幂等
        for (index in 1..<APP_LOG_FILES) {
            runCatching { SystemFileSystem.delete(Path(directory, "$APP_LOG.log-$index.log"), mustExist = false) }
        }
        for (index in 1..<ERROR_LOG_FILES) {
            runCatching { SystemFileSystem.delete(Path(directory, "$ERROR_LOG.log-$index.log"), mustExist = false) }
        }
        attachFileWriters(Path(directory))
    }

    /**
     * Mirrors the log onto disk under [directory]: Info and above into [APP_LOG], Error alone into
     * [ERROR_LOG]. Debug stays on Logcat/console only. Called once at startup by each app.
     *
     * Kermit's RollingFileLogWriter writes on a thread of its own, so a caller never waits on the
     * disk, and rolls each file over by size, so the folder never grows past about 4 MB.
     */
    fun enableFileLogging(directory: String) {
        if (logDirectory != null) return
        val path = Path(directory)
        // The writer opens its file but never creates the folder, and a missing one only drops lines.
        runCatching { SystemFileSystem.createDirectories(path) }
        logDirectory = directory
        attachFileWriters(path)
    }

    private fun attachFileWriters(path: Path) {
        fileWriters =
            listOf(
                LevelFileWriter(path, APP_LOG, rollOnSize = 1_000_000, maxLogFiles = APP_LOG_FILES, minSeverity = Severity.Info),
                LevelFileWriter(path, ERROR_LOG, rollOnSize = 512_000, maxLogFiles = ERROR_LOG_FILES, minSeverity = Severity.Error),
            )
        // 整体替换而非追加(CR 复核:反复删除会向 kermit 累积退役写入器)——摘掉全部
        // 旧 LevelFileWriter(含 retired),console/其它写入器原样保留。走
        // mutableConfig(companion 的 setLogWriters vararg 形状对 List 类型不友好)
        val others = logger.mutableConfig.logWriterList.filter { it !is LevelFileWriter }
        logger.mutableConfig.logWriterList = others + fileWriters
    }

    /**
     * The files behind [name] ([APP_LOG] or [ERROR_LOG]), oldest first, whether they exist yet or
     * not; empty before [enableFileLogging]. RollingFileLogWriter's own naming: `<name>.log` is the
     * live file, `<name>-N.log` the older ones.
     */
    fun logFiles(name: String): List<String> {
        val directory = logDirectory ?: return emptyList()
        val count = if (name == ERROR_LOG) ERROR_LOG_FILES else APP_LOG_FILES
        return (count - 1 downTo 0).map { index ->
            Path(directory, if (index == 0) "$name.log" else "$name-$index.log").toString()
        }
    }

    fun d(
        tag: String,
        message: String,
    ) {
        if (isMuted(tag)) return
        logger.d(
            tag = tag,
            message = {
                message
            },
        )
    }

    fun i(
        tag: String,
        message: String,
    ) {
        if (isMuted(tag)) return
        // 暂停门控已下沉到 LevelFileWriter.log(CR P2:漏斗层拦截连 console 镜像一起停)
        logger.i(tag = tag, message = { message })
    }

    fun w(
        tag: String,
        message: String,
    ) {
        if (isMuted(tag)) return
        logger.w(tag = tag, message = { message })
    }

    fun e(
        tag: String,
        message: String,
        e: Throwable? = null,
    ) {
        if (isMuted(tag)) return
        logger.e(throwable = e, tag = tag, message = { message })
    }
}

/**
 * 自研滚动文件写入器(CR 四复核:iOS 无反射 API 释放不了 kermit 的 RollingFileLogWriter,
 * 索性不再继承它——自己持有 scope/channel,关闭路径全平台可用,不再需要 expect/actual)。
 * 行为对齐 kermit 2.2.0:行格式 `<ISO_DATE_TIME_OFFSET 时间戳> L/Tag: message` +
 * throwable 堆栈行(AppLogFiles 的解析正则按此写);按字节滚动、重命名递增删最旧;
 * IOException 不中断循环(iOS 锁屏文件保护);写入在专用单线程协程,生产侧
 * trySend+DROP_OLDEST 永不阻塞打日志的线程(kermit 是阻塞背压,日志场景丢弃更合适)。
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class LevelFileWriter(
    private val directory: Path,
    private val name: String,
    private val rollOnSize: Long,
    private val maxLogFiles: Int,
    private val minSeverity: Severity,
) : LogWriter() {
    /** [Logger.clearLogFiles] 退役标志:停笔(双保险——真正摘除在 attachFileWriters 的整体替换) */
    @Volatile
    var retired: Boolean = false

    /** 与 [Logger.logFiles] 同一套命名:live=`<name>.log`,历史=`<name>-N.log`(CR 五复核:
     *  首版误用 liveFile.name 拼出 `app.log-1.log`,轮转文件读取/删除全找不到) */
    private fun pathForIndex(index: Int): Path =
        Path(directory, if (index == 0) "$name.log" else "$name-$index.log")

    private val liveFile = pathForIndex(0)

    @Volatile
    private var ioErrorActive = false

    // 不用 newSingleThreadContext(专用线程跨平台关不掉——Native 无 ExecutorCoroutineDispatcher,
    // iOS 反射也无从谈起):写盘协程是 channel 的唯一消费者,串行性由协程自身保证,
    // limitedParallelism(1) 的共享池视图功能等价且**零专属线程**,shutdown 无需关
    private val scope =
        CoroutineScope(
            Dispatchers.Default.limitedParallelism(1) +
                SupervisorJob() +
                CoroutineExceptionHandler { _, throwable ->
                    // can't log it, we're the logger -- print to standard error
                    throwable.printStackTrace()
                },
        )
    private val channel = Channel<String>(capacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    init {
        scope.launch { writerLoop() }
    }

    // 门控写在 log 本体(kermit 分发实测不查 isLoggable,2026-10-13 applog 停止按钮实证):
    // 暂停=只停落盘,console 写入器不受影响;retired=删除后旧写入器停笔
    // 🚨kermit 的 LogWriter.log 参数序是 (severity, **message**, tag, throwable)——message 在前!
    // 按惯常 (tag, message) 命名会把两个串位置对反,输出成 "W/<消息>: <tag>"
    override fun log(
        severity: Severity,
        message: String,
        tag: String,
        throwable: Throwable?,
    ) {
        if (retired || !com.maxrave.logger.Logger.fileLoggingEnabled || severity < minSeverity) return
        channel.trySend(formatLine(severity, tag, message, throwable))
    }

    private fun formatLine(
        severity: Severity,
        tag: String,
        message: String,
        throwable: Throwable?,
    ): String =
        buildString {
            append(Clock.System.now().toString())
            append(' ')
            appendLine(LogLineFormatter.formatMessage(severity, Tag(tag), Message(message)))
            if (throwable != null) appendLine(throwable.stackTraceToString())
        }

    private suspend fun writerLoop() {
        var sink: kotlinx.io.Sink? = null
        var size = fileSizeOrZero()
        try {
            for (line in channel) {
                try {
                    if (size > rollOnSize) {
                        runCatching { sink?.close() }
                        sink = null
                        rollFiles()
                    }
                    if (sink == null) {
                        sink = SystemFileSystem.sink(liveFile, append = true).buffered()
                        size = fileSizeOrZero()
                    }
                    val bytes = line.encodeToByteArray()
                    sink.write(bytes, 0, bytes.size)
                    sink.flush()
                    size += bytes.size
                    if (ioErrorActive) {
                        println("AppLogFileWriter: Log file access restored")
                        ioErrorActive = false
                    }
                } catch (e: IOException) {
                    // iOS: 文件保护在锁屏时拒绝访问——吞掉保住循环,解锁后自动恢复
                    if (!ioErrorActive) {
                        println("AppLogFileWriter: IOException writing log, some logs may be lost: ${e.message}")
                        ioErrorActive = true
                    }
                    runCatching { sink?.close() }
                    sink = null
                }
            }
        } finally {
            runCatching { sink?.close() }
        }
    }

    private fun fileSizeOrZero(): Long = SystemFileSystem.metadataOrNull(liveFile)?.size ?: 0L

    private fun rollFiles() {
        val oldest = pathForIndex(maxLogFiles - 1)
        if (SystemFileSystem.exists(oldest)) SystemFileSystem.delete(oldest)
        (0..<(maxLogFiles - 1)).reversed().forEach { index ->
            val source = pathForIndex(index)
            val target = pathForIndex(index + 1)
            if (SystemFileSystem.exists(source)) {
                runCatching { SystemFileSystem.atomicMove(source, target) }
            }
        }
    }

    /**
     * 退役并释放(全平台,无需反射、无专属线程可漏):先 cancel job → 再关 channel
     * (唤醒 park 的接收,for-loop 正常退出,finally 里关 sink)——协程在共享 Default 池上
     * 自然收尾,不留任何待清理的线程/调度器。
     */
    fun shutdown() {
        retired = true
        runCatching {
            scope.cancel()
            channel.close()
        }
    }
}

/**
 * One entry per line, logcat-style: `I/Tag: message`. The writer puts the timestamp in front and a
 * throwable's stack trace on the lines after, which is the shape the App log screen parses.
 */
private object LogLineFormatter : MessageStringFormatter {
    override fun formatMessage(
        severity: Severity?,
        tag: Tag?,
        message: Message,
    ): String = "${severity?.name?.first() ?: '-'}/${tag?.tag.orEmpty()}: ${message.message}"
}


enum class LogLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR,
}