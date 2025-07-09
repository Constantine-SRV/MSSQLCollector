package model;

/**
 * Описание одного SQL-запроса из конфигурационного файла.
 * {@code requestId} используется в имени выходного файла.
 */
public record QueryRequest(String requestId, String queryText) { }
