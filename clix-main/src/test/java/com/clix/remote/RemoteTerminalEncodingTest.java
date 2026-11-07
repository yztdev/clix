package com.clix.remote;

import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RemoteTerminalEncodingTest {
    @Test void readsChineseFromBothWindowsAndUtf8TerminalEncodings() throws Exception {
        String text = "将hello.txt中的内容改成你好";
        for (Charset charset : new Charset[]{Charset.forName("GBK"), StandardCharsets.UTF_8}) {
            var input = new ByteArrayInputStream((text + "\n").getBytes(charset));
            assertEquals(text, RemoteClientCommand.inputReader(input, charset).readLine());
        }
    }
}
