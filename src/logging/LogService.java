package logging;

import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Утилита для логирования сообщений (и ошибок) в консоль и файл.
 * Автоматически пишет имя вызывающего метода и время.
 * Использует FileLogWriter (singleton) для записи в файл лога.
 */
public class LogService {

    // Формат времени в логе
    private static final SimpleDateFormat TIME_FMT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");

    // ---------- Базовые методы ----------

    /** Логирование как System.out.printf (с переводом строки) */
    public static void printf(String format, Object... args) {
        String msg = String.format(format, args);
        writeLog(msg, false);
    }

    /** Логирование как System.out.println */
    public static void println(String msg) {
        writeLog(msg, false);
    }

    /** Логирование ошибок как System.err.printf (с переводом строки) */
    public static void errorf(String format, Object... args) {
        String msg = String.format(format, args);
        writeLog(msg, true);
    }

    /** Логирование ошибок как System.err.println */
    public static void errorln(String msg) {
        writeLog(msg, true);
    }

    // ---------- Внутренние методы ----------

    private static void writeLog(String msg, boolean isError) {
        String stamp = TIME_FMT.format(new Date());
        String caller = getCallerInfo();
        String line = String.format("[%s] [%s] %s", stamp, caller, msg);

        if (isError) {
            System.err.println(line);
        } else {
            System.out.println(line);
        }

        FileLogWriter flw = FileLogWriter.getInstance();
        if (flw != null) flw.log(line);
    }

    /** Определяет имя вызывающего метода и класса (1-2 уровня выше стека) */
    private static String getCallerInfo() {
        // [0] getStackTrace [1] getCallerInfo [2] writeLog [3] внешний вызов LogService...
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        if (stack.length > 4) {
            StackTraceElement caller = stack[4];
            String cls = caller.getClassName();
            // Можно вернуть только короткое имя класса:
            cls = cls.substring(cls.lastIndexOf('.') + 1);
            return cls + "." + caller.getMethodName();
        }
        return "Unknown";
    }

    /** Закрытие файла лога при завершении приложения (по желанию) */
    public static void closeFile() {
        FileLogWriter flw = FileLogWriter.getInstance();
        if (flw != null) flw.close();
    }
}
