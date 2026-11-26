package mauro.plugin.duckdb

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.Appender
import ch.qos.logback.core.FileAppender
import ch.qos.logback.core.filter.Filter
import ch.qos.logback.core.spi.FilterReply
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import org.slf4j.LoggerFactory

import java.nio.file.Files
import java.nio.file.Path

class CSVRunLogging {
    private static final String CONSOLE_APPENDER = 'STDOUT'
    private static final String FILE_APPENDER = 'CSV_RUN_FILE'
    private static QuietConsoleFilter quietFilter

    static void configure(Path logFile, boolean quiet) {
        Object factory = LoggerFactory.getILoggerFactory()
        if (!(factory instanceof LoggerContext)) return
        LoggerContext context = factory as LoggerContext
        Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME)
        Appender<ILoggingEvent> console = root.getAppender(CONSOLE_APPENDER)
        if (console != null) {
            if (quietFilter == null) {
                quietFilter = new QuietConsoleFilter()
                quietFilter.context = context
                quietFilter.start()
                console.addFilter(quietFilter)
            }
            quietFilter.quiet = quiet
        }
        Appender<ILoggingEvent> old = root.getAppender(FILE_APPENDER)
        if (old != null) {
            root.detachAppender(old)
            old.stop()
        }
        if (logFile != null) attachFileAppender(context, root, logFile)
    }

    private static void attachFileAppender(LoggerContext context, Logger root, Path logFile) {
        Path absolute = logFile.toAbsolutePath().normalize()
        if (absolute.parent != null) Files.createDirectories(absolute.parent)
        PatternLayoutEncoder encoder = new PatternLayoutEncoder()
        encoder.context = context
        encoder.pattern = '%date{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n'
        encoder.start()
        FileAppender<ILoggingEvent> appender = new FileAppender<ILoggingEvent>()
        appender.context = context
        appender.name = FILE_APPENDER
        appender.file = absolute.toString()
        appender.append = true
        appender.encoder = encoder
        appender.start()
        if (!appender.isStarted()) throw new IOException("Could not open log file '${absolute}'")
        root.addAppender(appender)
    }

    private static class QuietConsoleFilter extends Filter<ILoggingEvent> {
        boolean quiet
        @Override
        FilterReply decide(ILoggingEvent event) { quiet ? FilterReply.DENY : FilterReply.NEUTRAL }
    }
}
