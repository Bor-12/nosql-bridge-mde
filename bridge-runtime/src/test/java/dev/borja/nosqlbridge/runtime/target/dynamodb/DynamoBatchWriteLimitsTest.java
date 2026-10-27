package dev.borja.nosqlbridge.runtime.target.dynamodb;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.http.ExecutableHttpRequest;
import software.amazon.awssdk.http.HttpExecuteRequest;
import software.amazon.awssdk.http.HttpExecuteResponse;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.SdkHttpResponse;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutRequest;
import software.amazon.awssdk.services.dynamodb.model.WriteRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DynamoBatchWriteLimitsTest {

    @Test
    void wireSizeMatchesSdkAcrossUnicodeRangesInNamesAndValues() throws Exception {
        StringBuilder text = new StringBuilder();
        for (int codePoint = 0; codePoint <= Character.MAX_CODE_POINT; codePoint++) {
            if (codePoint < Character.MIN_SURROGATE || codePoint > Character.MAX_SURROGATE) {
                text.appendCodePoint(codePoint);
            }
        }

        assertWireSizeMatchesSdk(List.of(Map.of("é€😀\u0000\"\\",
                AttributeValue.builder().s(text.toString()).build())));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 4095, 4096, 4097})
    void wireSizeMatchesSdkAcrossBase64PaddingAndBufferBoundaries(int length) throws Exception {
        byte[] payload = new byte[length];
        java.util.Arrays.fill(payload, (byte) 0xff);

        assertWireSizeMatchesSdk(List.of(Map.of("binary",
                AttributeValue.builder().b(SdkBytes.fromByteArray(payload)).build())));
    }

    @Test
    void wireSizeMatchesSdkSerializationForNestedValuesAndEscapedText() throws Exception {
        Map<String, AttributeValue> attributes = Map.of(
                "text", AttributeValue.builder().s("é€😀\n\r\t\b\f\u0000\"\\/").build(),
                "number", AttributeValue.builder().n("-1.25E+10").build(),
                "binary", AttributeValue.builder().b(SdkBytes.fromByteArray(new byte[] {0, 1, 2, 3})).build(),
                "object", AttributeValue.builder().m(Map.of("nested", AttributeValue.builder().l(
                        AttributeValue.builder().bool(false).build(),
                        AttributeValue.builder().nul(true).build(),
                        AttributeValue.builder().m(Map.of()).build(),
                        AttributeValue.builder().l(List.of()).build()).build())).build());

        assertWireSizeMatchesSdk(List.of(attributes, attributes));
    }

    @Test
    void wireSizeMatchesSdkFor25LargeBinaryItems() throws Exception {
        List<Map<String, AttributeValue>> items = new ArrayList<>();
        AttributeValue payload = AttributeValue.builder()
                .b(SdkBytes.fromByteArray(new byte[400 * 1024 - 32])).build();
        for (int i = 0; i < 25; i++) {
            items.add(Map.of("id", AttributeValue.builder().s("P" + i).build(), "payload", payload));
        }

        assertTrue(assertWireSizeMatchesSdk(items) < DynamoBatchWriteLimits.MAX_REQUEST_BYTES);
    }

    @Test
    void wireSizeMatchesSdkWhenControlCharactersExceedRequestLimit() throws Exception {
        Map<String, AttributeValue> item = Map.of("payload",
                AttributeValue.builder().s("\u0000".repeat(350_000)).build());

        assertTrue(assertWireSizeMatchesSdk(java.util.Collections.nCopies(8, item))
                > DynamoBatchWriteLimits.MAX_REQUEST_BYTES);
    }

    private long assertWireSizeMatchesSdk(List<Map<String, AttributeValue>> items) throws Exception {
        SdkHttpClient transport = mock(SdkHttpClient.class);
        ExecutableHttpRequest execution = mock(ExecutableHttpRequest.class);
        long[] actualBytes = new long[1];
        when(transport.prepareRequest(any(HttpExecuteRequest.class))).thenAnswer(invocation -> {
            HttpExecuteRequest request = invocation.getArgument(0);
            try (InputStream stream = request.contentStreamProvider().orElseThrow().newStream()) {
                actualBytes[0] = stream.transferTo(java.io.OutputStream.nullOutputStream());
            }
            return execution;
        });
        when(execution.call()).thenReturn(HttpExecuteResponse.builder()
                .response(SdkHttpResponse.builder().statusCode(200).build())
                .responseBody(AbortableInputStream.create(new ByteArrayInputStream(
                        "{}".getBytes(StandardCharsets.UTF_8)))).build());
        List<WriteRequest> writes = items.stream().map(item -> WriteRequest.builder()
                .putRequest(PutRequest.builder().item(item).build()).build()).toList();
        try (DynamoDbClient client = DynamoDbClient.builder().httpClient(transport)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .region(Region.EU_WEST_1).build()) {
            client.batchWriteItem(BatchWriteItemRequest.builder().requestItems(Map.of("Products", writes)).build());
        }
        long estimatedBytes = DynamoBatchWriteLimits.envelopeBytes("Products") + items.size() - 1;
        for (Map<String, AttributeValue> item : items) {
            estimatedBytes += DynamoBatchWriteLimits.putBytes(item);
        }
        assertEquals(actualBytes[0], estimatedBytes);
        return actualBytes[0];
    }

    @Test
    void storedSizeUsesUtf8AndRawBinaryBytes() {
        assertEquals(5, DynamoBatchWriteLimits.itemBytes(Map.of("é", AttributeValue.builder().s("€").build())));
        assertEquals(4, DynamoBatchWriteLimits.itemBytes(Map.of("b",
                AttributeValue.builder().b(SdkBytes.fromByteArray(new byte[] {1, 2, 3})).build())));
    }

    @Test
    void includesNestedContainerAndElementOverhead() {
        AttributeValue value = AttributeValue.builder().m(Map.of("a", AttributeValue.builder()
                .l(AttributeValue.builder().bool(false).build(), AttributeValue.builder().nul(true).build()).build())).build();

        // Root name 1; map overhead 3 + element 1; nested name 1;
        // list overhead 3; two elements each with overhead 1 and value size 1.
        assertEquals(13, DynamoBatchWriteLimits.itemBytes(Map.of("m", value)));
    }

    @Test
    void numericStorageEstimateUsesSignificantDigitsInsteadOfDecimalTextLength() {
        assertEquals(4, DynamoBatchWriteLimits.itemBytes(Map.of("n", AttributeValue.builder().n("1").build())));
        assertEquals(4, DynamoBatchWriteLimits.itemBytes(Map.of("n", AttributeValue.builder().n("-1.00000").build())));
        assertEquals(22, DynamoBatchWriteLimits.itemBytes(Map.of("n",
                AttributeValue.builder().n("12345678901234567890123456789012345678").build())));
    }

    @Test
    void wireBoundIncludesJsonEscapingAndBase64Expansion() {
        long rawText = DynamoBatchWriteLimits.putBytes(Map.of("x", AttributeValue.builder().s("abc").build()));
        long escaped = DynamoBatchWriteLimits.putBytes(Map.of("x", AttributeValue.builder().s("\n\"\\").build()));
        long unicode = DynamoBatchWriteLimits.putBytes(Map.of("x", AttributeValue.builder().s("€€€").build()));
        long binary = DynamoBatchWriteLimits.putBytes(Map.of("x", AttributeValue.builder()
                .b(SdkBytes.fromByteArray(new byte[] {1, 2, 3})).build()));

        assertTrue(escaped > rawText);
        assertTrue(unicode > rawText);
        assertTrue(binary > rawText);
    }
}
