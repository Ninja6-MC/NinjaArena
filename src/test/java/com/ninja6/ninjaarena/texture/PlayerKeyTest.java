package com.ninja6.ninjaarena.texture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ninja6.ninjaarena.texture.PlayerKey.Edition;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PlayerKeyTest {

    @Test
    void javaAndBedrockKeysUseSeparateNamespaces() {
        UUID account = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
        assertEquals("java:123e4567-e89b-12d3-a456-426614174000", PlayerKey.java(account).toString());
        assertEquals("bedrock:2535412345678901", PlayerKey.bedrock("2535412345678901").toString());
        assertNotEquals(PlayerKey.java(account), PlayerKey.bedrock("123"));
    }

    @Test
    void storedFormRoundTripsAndNormalizesJavaCase() {
        PlayerKey java = PlayerKey.of(Edition.JAVA, "123E4567-E89B-12D3-A456-426614174000");
        assertEquals("java:123e4567-e89b-12d3-a456-426614174000", java.toString());
        assertEquals(java, PlayerKey.parse(java.toString()));
        PlayerKey bedrock = PlayerKey.bedrock(" 98765 ");
        assertEquals(bedrock, PlayerKey.parse("bedrock:98765"));
    }

    @Test
    void malformedIdentitiesAreRejectedRatherThanGuessed() {
        assertThrows(IllegalArgumentException.class, () -> PlayerKey.bedrock(null));
        assertThrows(IllegalArgumentException.class, () -> PlayerKey.bedrock(" "));
        assertThrows(IllegalArgumentException.class, () -> PlayerKey.bedrock("Steve"));
        assertThrows(IllegalArgumentException.class, () -> PlayerKey.bedrock("123456789012345678901"));
        assertThrows(IllegalArgumentException.class, () -> PlayerKey.of(Edition.JAVA, "Steve"));
        assertThrows(IllegalArgumentException.class, () -> PlayerKey.of(Edition.JAVA, "12345"));
        assertThrows(IllegalArgumentException.class, () -> PlayerKey.parse("steve"));
        assertThrows(IllegalArgumentException.class, () -> PlayerKey.parse("floodgate:123"));
    }

    @Test
    void onlyJavaKeysHaveAnAccount() {
        UUID account = UUID.randomUUID();
        assertEquals(account, PlayerKey.java(account).account().orElseThrow());
        assertEquals(java.util.Optional.empty(), PlayerKey.bedrock("1").account());
    }
}
