package logging;

import model.DestinationConfig;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Простой сервис логирования с поддержкой нескольких назначений:
 *   - Console
 *   - LOCALFILE
 *
 * Можно перечислять несколько типов через ';' / ',' / пробел:
 *   <Type>Console;LOCALFILE</Type>
 *
 * В файловом режиме создаёт ДВА файла в текущем каталоге:
 *   - log_yyyyMMdd_HHmmss.txt     — для обычных сообщений
 *   - error_yyyyMMdd_HHmmss.txt   — для ошибок
 *
 * Первая строка до init(...) уходит только в консоль — это ожидаемо.
 */
public final class LogService {

    private LogService() {}

    /* -------------------- состояние -------------------- */

    private static volatile boolean consoleEnabled = true;   // дефолт — консоль
    private static volatile boolean fileEnabled    = false;

    // Раздельные файлы для info и error
    private static volatile BufferedWriter infoWriter  = null;
    private static volatile BufferedWriter errorWriter = null;

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    /**
     * Инициализация из секции <LogsDestination>.
     * Можно вызывать повторно — старые файловые писатели закроются.
     */
    public static synchronized void init(DestinationConfig logsDest) {
        boolean nextConsole = true;   // по умолчанию — только консоль
        boolean nextFile = false;

        // Разбор типов
        if (logsDest != null && logsDest.type != null) {
            String typeRaw = logsDest.type.trim();
            if (!typeRaw.isEmpty()) {
                String[] parts = typeRaw.split("[;|,\\s]+");
                nextConsole = false; // если явно указаны типы — сбрасываем дефолт
                for (String p : parts) {
                    String t = p.trim().toUpperCase(Locale.ROOT);
                    if (t.equals("CONSOLE"))   nextConsole = true;
                    if (t.equals("LOCALFILE")) nextFile = true;
                }
            }
        }

        BufferedWriter nextInfo = null;
        BufferedWriter nextErr  = null;

        if (nextFile) {
            String ts = LocalDateTime.now().format(TS);
            String infoName  = "log_"   + ts + ".txt";
            String errorName = "error_" + ts + ".txt";
            try {
                nextInfo = new BufferedWriter(new FileWriter(infoName,  true));
                nextErr  = new BufferedWriter(new FileWriter(errorName, true));
            } catch (IOException e) {
                nextFile = false;
                System.err.printf("[LOG] Can't open log files: %s%n", e.getMessage());
            }
        }

        // Закрываем старые файлы, если были
        closeQuietly(infoWriter);
        closeQuietly(errorWriter);

        consoleEnabled = nextConsole;
        fileEnabled    = nextFile;
        infoWriter     = nextInfo;
        errorWriter    = nextErr;

        // Итоговый статус
        if (consoleEnabled && fileEnabled) {
            System.out.println("[LOG] Destination: Console + LocalFile");
        } else if (consoleEnabled) {
            System.out.println("[LOG] Destination: Console");
        } else if (fileEnabled) {
            System.out.println("[LOG] Destination: LocalFile only");
        } else {
            System.out.println("[LOG] Destination: (none) — enabling Console fallback");
            consoleEnabled = true;
        }
    }

    /* -------------------- публичный API (как раньше) -------------------- */

    public static void println(String s) {
        write(false, s + System.lineSeparator());
    }

    public static void printf(String fmt, Object... args) {
        write(false, String.format(fmt, args));
    }

    public static void errorln(String s) {
        write(true, s + System.lineSeparator());
    }

    public static void errorf(String fmt, Object... args) {
        write(true, String.format(fmt, args));
    }

    /** В проекте встречается — оставляем алиас */
    public static void error(String s) {
        errorln(s);
    }

    /* -------------------- ядро записи -------------------- */

    private static void write(boolean isError, String text) {
        // Консоль
        if (consoleEnabled) {
            if (isError) System.err.print(text);
            else         System.out.print(text);
        }

        // Файлы
        if (fileEnabled) {
            BufferedWriter w = isError ? errorWriter : infoWriter;
            if (w != null) {
                try {
                    synchronized (LogService.class) {
                        w.write(text);
                        w.flush();
                    }
                } catch (IOException e) {
                    // если файловая запись упала — предупреждаем и дублируем в консоль
                    System.err.printf("[LOG] File logging failed: %s%n", e.getMessage());
                    System.err.print(text);
                }
            }
        }
    }

    private static void closeQuietly(BufferedWriter w) {
        if (w == null) return;
        try { w.flush(); w.close(); } catch (Exception ignore) {}
    }
}
