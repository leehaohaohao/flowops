package com.nexa.flowops.controller;

import com.nexa.flowops.common.Result;
import com.nexa.flowops.service.MigrationService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/migration")
public class MigrationController {

    private final MigrationService migrationService;

    public MigrationController(MigrationService migrationService) {
        this.migrationService = migrationService;
    }

    @PostMapping("/port-mappings")
    public Result<String> migratePortMappings() {
        int migrated = migrationService.migratePortMappings();
        return Result.ok("迁移完成，共处理 " + migrated + " 条记录");
    }
}
