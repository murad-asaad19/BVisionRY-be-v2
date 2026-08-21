package com.bvisionry.exercise;

import com.bvisionry.common.exception.BadRequestException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Key validation for the §7 quality-tag set: what a reviewer may apply is
 * whatever the live config says, and a broken or missing config must fall back
 * rather than take the review screen down.
 */
@ExtendWith(MockitoExtension.class)
class QualityTagCatalogTest {

    @Mock private NamedParameterJdbcTemplate jdbc;

    private QualityTagCatalog catalog(String storedJson) {
        when(jdbc.<String>query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(storedJson == null ? List.of() : List.of(storedJson));
        return new QualityTagCatalog(jdbc);
    }

    @Test
    void noConfiguredRowFallsBackToTheShippedDefaults() {
        assertThat(catalog(null).tags())
                .extracting(t -> t.key())
                .containsExactly("thin", "adequate", "strong");
    }

    @Test
    void aConfiguredSetReplacesTheDefaultsEntirely() {
        QualityTagCatalog catalog = catalog(
                "{\"tags\":[{\"key\":\"weak\",\"label\":\"Weak\"},{\"key\":\"solid\",\"label\":\"Solid\"}]}");
        assertThat(catalog.requireLabel("solid")).isEqualTo("Solid");
        assertThatThrownBy(() -> catalog.requireLabel("thin"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("thin");
    }

    @Test
    void anUnparseableRowFallsBackInsteadOfThrowing() {
        assertThat(catalog("{ not json").tags()).hasSize(3);
    }

    /** An empty set would leave a reviewer with no pill at all — treat it as unset. */
    @Test
    void anEmptySetFallsBackToo() {
        assertThat(catalog("{\"tags\":[]}").tags()).hasSize(3);
    }

    @Test
    void anUnknownKeyIsRefusedWithTheKeyInTheMessage() {
        assertThatThrownBy(() -> catalog(null).requireLabel("brilliant"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("brilliant");
    }
}
