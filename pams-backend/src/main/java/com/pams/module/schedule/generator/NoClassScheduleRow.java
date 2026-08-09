package com.pams.module.schedule.generator;

import java.util.List;
import java.util.Map;

public record NoClassScheduleRow(int period, String label, String halfDay, Map<Integer, List<NoClassScheduleCell>> cells) {}
