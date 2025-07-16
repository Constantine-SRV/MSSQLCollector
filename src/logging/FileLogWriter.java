package logging;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Запись строк в обычный лог и лог ошибок. Реализует “ленивое” открытие.
 */
public class FileLogWriter {
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmm");
    private static final String LOG_PREFIX = "log_";
    private static final String ERR_PREFIX = "err_";
    private static final String EXT = ".log";

    private static BufferedWriter logWriter;
    private static BufferedWriter errWriter;

    // Получить имя файла по текущему времени
    private static String makeFileName(String prefix) {
        String ts = LocalDateTime.now().format(TS_FMT);
        return prefix + ts + EXT;
    }

    private static BufferedWriter getLogWriter() throws IOException {
        if (logWriter == null) {
            logWriter = Files.newBufferedWriter(Paths.get(makeFileName(LOG_PREFIX)),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        return logWriter;
    }

    private static BufferedWriter getErrWriter() throws IOException {
        if (errWriter == null) {
            errWriter = Files.newBufferedWriter(Paths.get(makeFileName(ERR_PREFIX)),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        return errWriter;
    }

    /** Записать строку в обычный лог (с таймштампом) */
    public static synchronized void write(String line) {
        try {
            getLogWriter().write(line);
            getLogWriter().flush();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /** Записать строку в лог ошибок (с таймштампом) */
    public static synchronized void writeErr(String line) {
        try {
            getErrWriter().write(line);
            getErrWriter().flush();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /** Закрыть все файлы (по завершении работы) */
    public static synchronized void close() {
        try { if (logWriter != null) logWriter.close(); } catch (Exception ignored) {}
        try { if (errWriter != null) errWriter.close(); } catch (Exception ignored) {}
    }
}
