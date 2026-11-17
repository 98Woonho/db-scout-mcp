package io.github._98woonho.dbscout.connection;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * jar 안에 든 Sakila 데모 DB(SQLite 파일)를 실제 파일로 꺼낸다.
 *
 * <p>SQLite는 파일 경로가 있어야 열 수 있으므로 임시 폴더에 복사한다.
 * 파일 이름에 내용 해시를 넣어, 같은 데이터면 다시 복사하지 않고 데이터가 바뀌면 새 파일을 쓴다.
 */
public final class SakilaDemo {

    public static final String CONNECTION_NAME = "sakila";
    public static final String DESCRIPTION =
            "내장 데모 DB. 공개 샘플 DB Sakila(DVD 대여점: 영화·배우·고객·대여·결제)의 SQLite판";
    static final String RESOURCE = "/demo/sakila.db";

    private SakilaDemo() {
    }

    /** 데모 DB를 꺼내고 JDBC URL을 돌려준다. */
    public static String jdbcUrl() {
        return "jdbc:sqlite:" + extract(defaultDir()).toAbsolutePath();
    }

    static Path defaultDir() {
        return Path.of(System.getProperty("java.io.tmpdir"), "db-scout");
    }

    static Path extract(Path dir) {
        byte[] bytes = readResource();
        Path target = dir.resolve("sakila-" + shortHash(bytes) + ".db");
        try {
            if (Files.exists(target) && Files.size(target) == bytes.length) {
                return target;
            }
            Files.createDirectories(dir);
            // 여러 서버가 동시에 시작해도 반쯤 쓴 파일을 열지 않도록, 임시 파일에 쓴 뒤 한 번에 옮긴다
            Path tmp = Files.createTempFile(dir, "sakila-", ".tmp");
            Files.write(tmp, bytes);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                Files.deleteIfExists(tmp);
                if (!Files.exists(target)) throw e;
            }
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException("Sakila 데모 DB를 임시 폴더에 꺼내지 못했습니다: " + dir, e);
        }
    }

    private static byte[] readResource() {
        try (InputStream in = SakilaDemo.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("jar 안에 " + RESOURCE + " 가 없습니다. Gradle로 빌드했는지 확인하세요.");
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String shortHash(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            return HexFormat.of().formatHex(digest, 0, 6);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
