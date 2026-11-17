package io.github._98woonho.dbscout.mask;

/** 개인정보를 가리는 방식. 뒤에 있을수록 더 많이 가린다. */
public enum MaskKind {
    PARTIAL("일부 가림"),
    BIRTH("생년월일"),
    PHONE("전화번호"),
    EMAIL("이메일"),
    SECRET("전부 가림");

    private final String label;

    MaskKind(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** 둘 중 더 많이 가리는 쪽. null은 '가리지 않음'. */
    public static MaskKind strongest(MaskKind a, MaskKind b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.ordinal() >= b.ordinal() ? a : b;
    }
}
