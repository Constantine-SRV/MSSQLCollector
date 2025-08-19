package logging;

import model.DestinationConfig;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Сервис логирования с поддержкой нескольких назначений:
 *   - Console
 *   - LOCALFILE
 *
 * Можно перечислять несколько типов через ';' / ',' / пробел:
 *   <Type>Console;LOCALFILE</Type>
 *
 * В файловом режиме:
 *   - log_<sessionTimestamp>.txt     — обычные сообщения (создаётся при init)
 *   - error_<sessionTimestamp>.txt   — ошибки (создаётся ЛЕНИВО при первой ошибке)
 */
public final class LogService {

    private LogService() {}

    /* -------------------- состояние -------------------- */

    private static volatile boolean consoleEnabled = true;   // дефолт — консоль
    private static volatile boolean fileEnabled    = false;

    private static volatile BufferedWriter infoWriter  = null;
    private static volatile BufferedWriter errorWriter = null;

    // Единый таймстамп сессии для согласованных имён файлов
    private static volatile String sessionTs = null;
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

        // Всегда новый таймстамп на инициализацию (для парных имён)
        sessionTs = LocalDateTime.now().format(TS);

        if (nextFile) {
            String infoName  = "log_" + sessionTs + ".txt";
            try {
                nextInfo = new BufferedWriter(new FileWriter(infoName, true));
            } catch (IOException e) {
                nextFile = false;
                System.err.printf("[LOG] Can't open log file '%s': %s%n", infoName, e.getMessage());
            }
        }

        // Закрываем старые файлы, если были
        closeQuietly(infoWriter);
        closeQuietly(errorWriter);

        consoleEnabled = nextConsole;
        fileEnabled    = nextFile;
        infoWriter     = nextInfo;
        errorWriter    = null; // сбрасываем — теперь он ленивый

        // Итоговый статус
        if (consoleEnabled && fileEnabled) {
            System.out.println("[LOG] Destination: Console + LocalFile (split info/error, lazy error file)");
        } else if (consoleEnabled) {
            System.out.println("[LOG] Destination: Console");
        } else if (fileEnabled) {
            System.out.println("[LOG] Destination: LocalFile only (split info/error, lazy error file)");
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
            try {
                if (isError) {
                    ensureErrorWriter(); // лениво создаём error-файл
                }
                BufferedWriter w = isError ? errorWriter : infoWriter;
                if (w != null) {
                    synchronized (LogService.class) {
                        w.write(text);
                        w.flush();
                    }
                }
            } catch (IOException e) {
                // если файловая запись упала — предупредим и дублируем в консоль
                System.err.printf("[LOG] File logging failed: %s%n", e.getMessage());
                System.err.print(text);
            }
        }
    }

    // Ленивая и потокобезопасная инициализация errorWriter
    private static void ensureErrorWriter() throws IOException {
        if (errorWriter != null) return;
        synchronized (LogService.class) {
            if (errorWriter == null) {
                // sessionTs уже установлен при init
                String errorName = "error_" + sessionTs + ".txt";
                errorWriter = new BufferedWriter(new FileWriter(errorName, true));
            }
        }
    }

    private static void closeQuietly(BufferedWriter w) {
        if (w == null) return;
        try { w.flush(); w.close(); } catch (Exception ignore) {}
    }
}
