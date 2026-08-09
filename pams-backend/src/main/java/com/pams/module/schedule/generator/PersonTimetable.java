package com.pams.module.schedule.generator;

import java.util.List;
import java.util.Map;

public record PersonTimetable(String name, Map<SlotKey, List<Course>> timetable) {}
