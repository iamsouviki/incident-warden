package com.company.warden.service;

import com.company.warden.config.CurrentUser;
import com.company.warden.model.Skill;
import com.company.warden.repository.SkillRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SkillServiceTest {
    @Test
    void resolvesConfiguredFieldsAndDefaultsWithoutPromptingForBooleanFlags() {
        Skill extraction = new Skill();
        extraction.setId(UUID.randomUUID());
        extraction.setKind(SkillService.EXTRACTION);
        extraction.setSkillKey("POG_ISSUE");
        extraction.setEnabled(true);
        extraction.setDefinitionJson("""
                {"fields":[
                  {"key":"StoreNumber","label":"Store number","required":true,"pattern":"store\\\\s*(\\\\d+)"},
                  {"key":"LabelPrintIssueFlag","label":"Label printing issue","type":"boolean","required":true,"default":"false","pattern":"print"}
                ]}
                """);

        SkillRepository repository = mock(SkillRepository.class);
        when(repository.findByKindAndSkillKey(SkillService.EXTRACTION, "POG_ISSUE"))
                .thenReturn(Optional.of(extraction));
        SkillService service = new SkillService(repository, mock(CurrentUser.class), mock(AuditService.class), new ObjectMapper());

        SkillService.RuleResolution result = service.resolve("POG_ISSUE", "Store 4022 has a POG issue");

        assertThat(result.values()).containsEntry("StoreNumber", "4022")
                .containsEntry("LabelPrintIssueFlag", "false");
        assertThat(result.missing()).extracting(field -> field.get("key")).containsExactly();
    }
}
