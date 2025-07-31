package logging;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Универсальный сервис логирования.
 * Пишет в консоль + в файл (FileLogWriter).
 * Для ошибок дополнительно фиксирует класс / метод вызова.
 */
public final class LogService {

    private LogService() {}                    // утилитарный класс

    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    /* ====================================================================
                                INFO-логи
       ==================================================================== */

    /** printf-style без перевода строки (как System.out.printf) */
    public static void printf(String fmt, Object... args) {
        String msg = String.format(fmt, args);
        writeStd(msg, false);
    }

    /** println-style (как System.out.println) */
    public static void println(String msg) {
        writeStd(msg, true);
    }

    /* ====================================================================
                                ERROR-логи
       ==================================================================== */

    /** println с пометкой ERROR и подписью места вызова */
    public static void error(String msg) {
        writeErr("ERROR: " + msg, true);
    }

    /** форматированный ERROR (как printf) */
    public static void errorf(String fmt, Object... args) {
        writeErr("ERROR: " + String.format(fmt, args), true);
    }

    /** error-вариант без «ERROR:», но с подписью класса/метода */
    public static void errorln(String msg) {
        writeErr(msg, true);
    }

    /* ==================================================================== */
    /*                          ВНУТРЕННЯЯ КУХНЯ                            */
    /* ==================================================================== */

    /** выводит в System.out + обычный лог-файл */
    private static void writeStd(String msg, boolean addLn) {
        String ts = ts();
        if (addLn && !msg.endsWith("\n")) msg += "\n";
        System.out.print(ts + msg);
        FileLogWriter.write(ts + msg);
    }

    /** выводит в System.err + err-файл, добавляя info о месте вызова */
    private static void writeErr(String msg, boolean addLn) {
        String ts = ts();
        String from = callerInfo();
        String full = ts + from + ' ' + msg;
        if (addLn && !full.endsWith("\n")) full += "\n";

        System.err.print(full);
        FileLogWriter.writeErr(full);
    }

    /** timestamp в квадратных скобках */
    private static String ts() {
        return "[" + LocalDateTime.now().format(TS_FMT) + "] ";
    }

    /** [Class.method:line] откуда нас вызвали (пропускаем системные фреймы) */
    private static String callerInfo() {
        StackTraceElement[] st = Thread.currentThread().getStackTrace();
        // 0 — getStackTrace, 1 — callerInfo, 2 — write*, 3 — public метод LogService,
        // 4 — уже настоящий внешний вызывающий код
        int idx = st.length > 4 ? 4 : st.length - 1;
        StackTraceElement el = st[idx];
        return "[" +
                simpleClassName(el.getClassName()) + '.' +
                el.getMethodName() + ':' +
                el.getLineNumber() + "]";
    }

    /** короткое имя класса без пакета */
    private static String simpleClassName(String fqcn) {
        int dot = fqcn.lastIndexOf('.');
        return (dot >= 0) ? fqcn.substring(dot + 1) : fqcn;
    }

    /** закрыть файловые ручки при завершении приложения */
    public static void close() {
        FileLogWriter.close();
    }
}
