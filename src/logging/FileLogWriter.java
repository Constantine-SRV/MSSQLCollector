package logging;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Потокобезопасный логгер для записи в файл.
 * Файл создаётся в корне приложения, имя — YYYYMMDD_HHmm.log времени старта.
 * Использует singleton-паттерн.
 */
public class FileLogWriter {
    private static FileLogWriter instance;
    private final BufferedWriter writer;
    private final String logFileName;

    private FileLogWriter() throws IOException {
        String ts = new SimpleDateFormat("yyyyMMdd_HHmm").format(new Date());
        logFileName = ts + ".log";
        File file = new File(logFileName);
        writer = new BufferedWriter(new FileWriter(file, true)); // append
    }

    public static synchronized FileLogWriter getInstance() {
        if (instance == null) {
            try {
                instance = new FileLogWriter();
            } catch (IOException ex) {
                // Не удалось создать файл — можно просто печатать в консоль ошибку
                System.err.println("[FileLogWriter] Can't create log file: " + ex.getMessage());
                instance = null;
            }
        }
        return instance;
    }

    public synchronized void log(String msg) {
        if (writer == null) return;
        try {
            writer.write(msg);
            writer.newLine();
            writer.flush();
        } catch (IOException ex) {
            System.err.println("[FileLogWriter] Write error: " + ex.getMessage());
        }
    }

    /** Имя файла лога, если надо показать где файл */
    public String getLogFileName() {
        return logFileName;
    }

    /** Корректное закрытие при завершении приложения */
    public void close() {
        try {
            if (writer != null) writer.close();
        } catch (IOException ignored) {}
    }
}
