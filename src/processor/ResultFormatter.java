package processor;

import java.io.IOException;
import java.io.Writer;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Сериализатор ResultSet в текст. Используется ResponseProcessor для записи
 * результата в файл/БД. Имеет две реализации: XML и JSON.
 */
public interface ResultFormatter {

    /** Результат сериализации в строку: тело + количество обработанных строк. */
    record FormatResult(String body, int rowCount) {}

    /**
     * Стримит результат в Writer (используется при сохранении в файл).
     * @return количество обработанных строк (rows)
     */
    int streamTo(String ci, String reqId, ResultSet rs, Writer w) throws SQLException, IOException;

    /**
     * Полностью формирует тело в строке (используется для INSERT в БД).
     */
    FormatResult format(String ci, String reqId, ResultSet rs) throws SQLException;

    /** Расширение файла для данного формата (например, ".xml" / ".json"). */
    String fileExtension();
}
