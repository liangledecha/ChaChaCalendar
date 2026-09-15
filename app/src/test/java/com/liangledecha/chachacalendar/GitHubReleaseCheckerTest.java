package com.liangledecha.chachacalendar;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** 验证更新提示使用数字比较，而不是容易把 1.10.10 判小的字符串比较。 */
public final class GitHubReleaseCheckerTest {
    @Test public void comparesNumericVersionParts() {
        assertTrue(GitHubReleaseChecker.isNewer("v1.10.10", "1.10.9"));
        assertTrue(GitHubReleaseChecker.isNewer("1.11", "1.10.7"));
        assertFalse(GitHubReleaseChecker.isNewer("v1.10.7", "1.10.7"));
        assertFalse(GitHubReleaseChecker.isNewer("1.10.6", "1.10.7"));
    }
}
