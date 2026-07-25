package com.labex.labexagent.commandsecurity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class DirectCommandTokenizerTest {

    @Test
    void splitsRestrictedDirectCommandIntoArgv() {
        assertEquals(List.of("git", "status", "--short"),
                DirectCommandTokenizer.tokenize("git   status --short"));
    }

    @Test
    void rejectsShellSyntaxAndWorkspaceEscapingOperands() {
        for (String command : List.of(
                "git status && git diff", "git status; git diff", "git status | cat",
                "echo $HOME", "echo $(whoami)", "echo `whoami`", "echo hello > out.txt",
                "echo \"hello world\"", "echo hello\\ world", "cat ../other-project/secret.txt",
                "cat /etc/passwd", "type C:/Windows/win.ini")) {
            assertThrows(IllegalArgumentException.class, () -> DirectCommandTokenizer.tokenize(command), command);
        }
    }
}
