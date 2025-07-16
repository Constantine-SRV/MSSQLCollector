package logging;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Универсальный сервис логирования. Пишет в консоль и в лог-файл.
 * Для ошибок — в отдельный файл.
 */
public class LogService {
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    /** Форматированный вывод в обычный лог */
    public static void printf(String fmt, Object... args) {
        String msg = String.format(fmt, args);
        String ts = "[" + LocalDateTime.now().format(TS_FMT) + "] ";
        System.out.print(ts + msg);
        FileLogWriter.write(ts + msg);
    }

    /** Форматированный вывод в лог ошибок */
    public static void errorf(String fmt, Object... args) {
        String msg = String.format(fmt, args);
        String ts = "[" + LocalDateTime.now().format(TS_FMT) + "] ";
        System.err.print(ts + msg);
        FileLogWriter.writeErr(ts + msg);
    }

    /** Обычный println (без форматирования) */
    public static void println(String msg) {
        String ts = "[" + LocalDateTime.now().format(TS_FMT) + "] ";
        System.out.println(ts + msg);
        FileLogWriter.write(ts + msg + "\n");
    }

    /** println для ошибок */
    public static void errorln(String msg) {
        String ts = "[" + LocalDateTime.now().format(TS_FMT) + "] ";
        System.err.println(ts + msg);
        FileLogWriter.writeErr(ts + msg + "\n");
    }

    /** Закрытие файлов логов (вызывать в конце работы приложения) */
    public static void close() {
        FileLogWriter.close();
    }
}
