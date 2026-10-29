package com.pams.common;

import java.sql.Timestamp;
import java.time.LocalDateTime;

/** DATETIME getObject differs between Connector/J and H2; keep the local-time semantics. */
public final class JdbcDateTimes {
  private JdbcDateTimes() {}
  public static LocalDateTime local(Object value) {
    if(value==null)return null;
    if(value instanceof LocalDateTime time)return time;
    if(value instanceof Timestamp time)return time.toLocalDateTime();
    throw new IllegalArgumentException("Unsupported JDBC local date-time value");
  }
}
