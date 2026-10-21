package com.pams.module.org.dto;

import java.util.List;

/** Complete, tenant-scoped directory, including disabled accounts for accurate relationship counts. */
public record OrgStructureVO(List<OrgUnitVO> units, List<OrgAccountVO> accounts) {}
