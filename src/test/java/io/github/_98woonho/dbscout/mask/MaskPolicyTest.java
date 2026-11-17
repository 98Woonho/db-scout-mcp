package io.github._98woonho.dbscout.mask;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

class MaskPolicyTest {

    private final MaskPolicy defaults = MaskPolicy.defaults();

    @Test
    void defaultRulesMatchColumnNameEndings() {
        assertThat(defaults.kindFor(null, "email")).isEqualTo(MaskKind.EMAIL);
        assertThat(defaults.kindFor(null, "EMAIL_ADDRESS")).isEqualTo(MaskKind.EMAIL);
        assertThat(defaults.kindFor(null, "phone_number")).isEqualTo(MaskKind.PHONE);
        assertThat(defaults.kindFor(null, "phoneNumber")).isEqualTo(MaskKind.PHONE); // camelCase
        assertThat(defaults.kindFor(null, "mobile")).isEqualTo(MaskKind.PHONE);
        assertThat(defaults.kindFor(null, "password")).isEqualTo(MaskKind.SECRET);
        assertThat(defaults.kindFor(null, "password_hash")).isEqualTo(MaskKind.SECRET);
        assertThat(defaults.kindFor(null, "api_key")).isEqualTo(MaskKind.SECRET);
        assertThat(defaults.kindFor(null, "birth_date")).isEqualTo(MaskKind.BIRTH);
        assertThat(defaults.kindFor(null, "address")).isEqualTo(MaskKind.PARTIAL);
        assertThat(defaults.kindFor(null, "zip code")).isEqualTo(MaskKind.PARTIAL); // 공백
        assertThat(defaults.kindFor(null, "\"postal_code\"")).isEqualTo(MaskKind.PARTIAL); // 따옴표
    }

    @Test
    void keyAndFlagColumnsAreNotMasked() {
        assertThat(defaults.kindFor(null, "address_id")).isNull();
        assertThat(defaults.kindFor(null, "email_verified")).isNull();
        assertThat(defaults.kindFor(null, "phone_count")).isNull();
        assertThat(defaults.kindFor(null, "first_name")).isNull(); // 이름은 기본 규칙에 없음(배우 이름 등 공개 정보)
        assertThat(defaults.kindFor(null, "author")).isNull();
        assertThat(defaults.kindFor(null, "tokens_used")).isNull();
    }

    @Test
    void tableSpecificRulesNeedTheTable() {
        var policy = MaskPolicy.of(List.of("customer.first_name", "member.nickname=secret", "memo"), List.of());

        assertThat(policy.kindFor("customer", "first_name")).isEqualTo(MaskKind.PARTIAL);
        assertThat(policy.kindFor("CUSTOMER", "FIRST_NAME")).isEqualTo(MaskKind.PARTIAL);
        assertThat(policy.kindFor("actor", "first_name")).isNull();
        assertThat(policy.kindFor(null, "first_name")).isNull();
        assertThat(policy.kindFor("member", "nickname")).isEqualTo(MaskKind.SECRET);
        assertThat(policy.kindFor("anything", "memo")).isEqualTo(MaskKind.PARTIAL);
        assertThat(policy.kindFor(null, "memo")).isEqualTo(MaskKind.PARTIAL);
    }

    @Test
    void unmaskOverridesDefaults() {
        var policy = MaskPolicy.of(List.of(), List.of("store.address", "fax"));

        assertThat(policy.kindFor("store", "address")).isNull();
        assertThat(policy.kindFor("customer_address", "address")).isEqualTo(MaskKind.PARTIAL);
        assertThat(policy.kindFor("office", "fax")).isNull();
    }

    @Test
    void rejectsUnknownKind() {
        assertThatThrownBy(() -> MaskPolicy.of(List.of("a.b=hidden"), List.of()))
                .hasMessageContaining("secret, email");
    }

    @Test
    void maskerKeepsShapeButHidesPerson() {
        assertThat(Masker.mask("MARY.SMITH@sakilacustomer.org", MaskKind.EMAIL, false))
                .isEqualTo("M***@sakilacustomer.org");
        assertThat(Masker.mask("010-1234-5678", MaskKind.PHONE, false)).isEqualTo("***-****-5678");
        assertThat(Masker.mask(1012345678L, MaskKind.PHONE, false)).isEqualTo("******5678");
        assertThat(Masker.mask("1990-05-03", MaskKind.BIRTH, false)).isEqualTo("1990-**-**");
        assertThat(Masker.mask("47 MySakila Drive", MaskKind.PARTIAL, false)).isEqualTo("47 ***");
        assertThat(Masker.mask("MARY", MaskKind.PARTIAL, false)).isEqualTo("M***");
        assertThat(Masker.mask("8cb2237d0679ca88db6464eac60da96345513964", MaskKind.SECRET, false))
                .isEqualTo(Masker.HIDDEN);
        assertThat(Masker.mask(null, MaskKind.SECRET, false)).isNull();
    }

    @Test
    void derivedValuesArePartiallyMasked() {
        // UPPER(email) 처럼 형식이 바뀌었을 수 있는 값은 이메일 형식으로 가리지 않고 앞부분만 남긴다
        assertThat(Masker.mask("MARY.SMITH@sakilacustomer.org", MaskKind.EMAIL, true)).isEqualTo("MAR***");
        assertThat(Masker.mask("secret", MaskKind.SECRET, true)).isEqualTo(Masker.HIDDEN);
    }
}
