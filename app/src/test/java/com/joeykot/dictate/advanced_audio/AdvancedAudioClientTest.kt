package com.joeykot.dictate.advanced_audio

import com.joeykot.dictate.advanced_audio.http.AdvancedCancellationToken
import com.joeykot.dictate.advanced_audio.http.AdvancedCancellationSource
import com.joeykot.dictate.advanced_audio.http.AdvancedHttpMethod
import com.joeykot.dictate.advanced_audio.http.AdvancedHttpTransport
import com.joeykot.dictate.advanced_audio.http.HttpBodySpec
import com.joeykot.dictate.advanced_audio.http.HttpHeader
import com.joeykot.dictate.advanced_audio.http.HttpDiagnosticEvent
import com.joeykot.dictate.advanced_audio.http.HttpRequestSpec
import com.joeykot.dictate.advanced_audio.http.HttpResponse
import com.joeykot.dictate.advanced_audio.http.HttpTransportException
import com.joeykot.dictate.model.AdvancedAudioConfig
import com.joeykot.dictate.model.AdvancedRemoteAudioConfig
import com.joeykot.dictate.model.RetryConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.UnknownHostException
import java.net.ConnectException

class AdvancedAudioClientTest {
    @Test
    fun diagnosticsRetainEachRetryAndTheFinalCauseWithoutSecrets() = withAudio { audio ->
        val events = mutableListOf<HttpDiagnosticEvent>()
        val transport = FakeTransport(
            ScriptedOutcome.Failure(HttpTransportException.NetworkFailure(UnknownHostException("private-host"))),
            ScriptedOutcome.Response(response(503, "private-server-error")),
            ScriptedOutcome.Failure(HttpTransportException.NetworkFailure(ConnectException("private-token ECONNREFUSED"))),
        )
        val result = run(workflowConfig(requestRecognition()), transport, audio, retrying(), onDiagnostic = { events += it })
        assertTrue(result is AdvancedAudioClient.Result.Failure)
        val retries = events.filter { it.message.contains("retry_scheduled") }
        assertEquals(2, retries.size)
        assertTrue(retries[0].message.contains("phase=request attempt=1 request=1"))
        assertTrue(retries[0].message.contains("nextAttempt=2 delayMs=0 kind=dns"))
        assertTrue(retries[1].message.contains("phase=request attempt=2 request=2"))
        assertTrue(events.any { it.message.contains("response status=503") })
        val failure = events.single { it.message.contains("workflow_failure") }
        assertTrue(failure.isError)
        assertTrue(failure.message.contains("phase=request attempt=3 request=3"))
        assertTrue(failure.message.contains("kind=connect causes=NetworkFailure>ConnectException errno=ECONNREFUSED"))
        assertTrue(events.last().message.contains("workflow_end outcome=failure status=none placeholderEligible=true"))
        assertFalse(events.any { it.message.contains("private") || it.message.contains("workflow.test") })
    }

    @Test
    fun diagnosticsDistinguishHttpErrorsAndParsingFailuresFromSuccessAndNoSpeech() = withAudio { audio ->
        for ((status, body, outcome) in listOf(
            Triple(401, "private-error", "failure"),
            Triple(200, "private-malformed-json", "failure"),
            Triple(200, """{"text":"private transcript"}""", "success"),
            Triple(200, """{"text":null}""", "success"),
        )) {
            val events = mutableListOf<HttpDiagnosticEvent>()
            run(workflowConfig(requestRecognition()), FakeTransport(ScriptedOutcome.Response(response(status, body))),
                audio, onDiagnostic = { events += it })
            assertTrue(events.last().message, events.last().message.contains("workflow_end outcome=$outcome status=$status"))
            if (body == "private-malformed-json") {
                assertTrue(events.any { it.message.contains("Extraction>InvalidJson") })
            }
            if (body.contains("null")) assertTrue(events.last().message.contains("noSpeech=true"))
            assertFalse(events.any { it.message.contains("private") })
        }
    }

    @Test
    fun asyncSubmitDiagnosticsReportFailureWithoutSuggestingARetry() = withAudio { audio ->
        val events = mutableListOf<HttpDiagnosticEvent>()
        val transport = FakeTransport(ScriptedOutcome.Failure(HttpTransportException.NetworkFailure(UnknownHostException())))
        run(workflowConfig(asyncRecognition()), transport, audio, retrying(), onDiagnostic = { events += it })
        assertEquals(1, transport.requests.size)
        assertFalse(events.any { it.message.contains("retry_scheduled") })
        assertTrue(events.last().message.contains("phase=submit attempt=1 request=1 workflow_end outcome=failure"))
    }

    @Test
    fun cancellationAndConfigurationFailureAlwaysEndTheDiagnosticLifecycle() = withAudio { audio ->
        for (cancel in listOf(false, true)) {
            val events = mutableListOf<HttpDiagnosticEvent>()
            val source = AdvancedCancellationSource()
            if (cancel) source.cancel()
            AdvancedAudioClient(FakeTransport()).transcribe(
                AdvancedAudioClient.Request(AdvancedAudioConfig(enabled = false), emptyMap(), audio, "audio/wav", onDiagnostic = { events += it }),
                source.token,
            )
            assertEquals(1, events.count { it.message.contains("workflow_start") })
            assertEquals(1, events.count { it.message.contains("workflow_end") })
            assertTrue(events.last().message.contains("outcome=${if (cancel) "cancelled" else "failure"}"))
        }
    }

    @Test
    fun requestReturnsTheExtractedFinalText() = withAudio { audio ->
        val transport = FakeTransport(ScriptedOutcome.Response(response(200, "{\"text\":\"synchronous result\"}")))
        val result = run(workflowConfig(requestRecognition()), transport, audio)

        assertTrue(result is AdvancedAudioClient.Result.Success)
        assertEquals("synchronous result", (result as AdvancedAudioClient.Result.Success).text)
        assertEquals(200, result.statusCode)
        assertEquals(1, transport.requests.size)
        assertEquals(AdvancedHttpMethod.POST, transport.requests.single().method)
    }

    @Test
    fun requestAndAsyncResultsAcceptEmptyWhitespaceAndNullTextWithoutRetrying() = withAudio { audio ->
        listOf("\"\"" to "", "\" \\t\\n\"" to " \t\n", "null" to "").forEach { (field, expected) ->
            val body = """{"text":$field}"""
            val cases = listOf(
                requestRecognition() to listOf(response(200, body)),
                asyncRecognition() to listOf(response(202, body)),
                asyncRecognition(withPoll = true) to listOf(
                    response(202, """{"task":"one"}"""),
                    response(200, """{"status":"succeeded","text":$field}"""),
                ),
                asyncRecognition(withResultStep = true) to listOf(
                    response(202, """{"task":"one"}"""), response(200, body),
                ),
            )
            cases.forEach { (recognition, responses) ->
                val transport = FakeTransport(*responses.map(ScriptedOutcome::Response).toTypedArray())
                val result = run(workflowConfig(recognition), transport, audio, retrying())
                assertTrue("$recognition $body: $result", result is AdvancedAudioClient.Result.Success)
                assertEquals(expected, (result as AdvancedAudioClient.Result.Success).text)
                assertEquals(responses.size, transport.requests.size)
            }
        }
    }

    @Test
    fun noWordsResponseCompletesEachAsrHttpStageWithoutRetryingOrRunningLaterStages() = withAudio { audio ->
        val noWords = response(400, """{"error":{"code":"ASR_RESPONSE_HAVE_NO_WORDS"}}""")
        val submitted = response(202, """{"task":"one"}""")
        val cases = listOf(
            requestRecognition() to listOf(noWords),
            streamRecognition() to listOf(noWords),
            streamRecognition().let { it.copy(request = it.request.copy(acceptedStatuses = listOf(400))) } to listOf(noWords),
            asyncRecognition(withPoll = true, withResultStep = true) to listOf(noWords),
            asyncRecognition(withPoll = true, withResultStep = true) to listOf(submitted, noWords),
            asyncRecognition(withResultStep = true) to listOf(submitted, noWords),
        )
        cases.forEach { (recognition, responses) ->
            val transport = FakeTransport(*responses.map(ScriptedOutcome::Response).toTypedArray())
            val result = run(workflowConfig(recognition), transport, audio, retrying())
            assertTrue("$recognition: $result", result is AdvancedAudioClient.Result.Success)
            assertEquals("", (result as AdvancedAudioClient.Result.Success).text)
            assertEquals(400, result.statusCode)
            assertEquals(responses.size, transport.requests.size)
        }
    }

    @Test
    fun pollNoWordsRequiresATerminalFailureAndDoesNotMaskPendingOrOtherFailures() = withAudio { audio ->
        val submitted = ScriptedOutcome.Response(response(202, """{"task":"one"}"""))
        val pollWorkflow = asyncRecognition(withPoll = true).let { recognition ->
            recognition.copy(poll = requireNotNull(recognition.poll).copy(timeoutMs = 1_000))
        }
        val noSpeechTransport = FakeTransport(
            submitted,
            ScriptedOutcome.Response(response(200, """{"status":"pending","code":"ASR_RESPONSE_HAVE_NO_WORDS"}""")),
            ScriptedOutcome.Response(response(200, """{"status":"failed","code":"ASR_RESPONSE_HAVE_NO_WORDS"}""")),
        )
        val noSpeech = run(workflowConfig(pollWorkflow), noSpeechTransport, audio, retrying())
        assertTrue(noSpeech is AdvancedAudioClient.Result.Success)
        assertEquals("", (noSpeech as AdvancedAudioClient.Result.Success).text)
        assertEquals(200, noSpeech.statusCode)
        assertEquals(3, noSpeechTransport.requests.size)

        val failedTransport = FakeTransport(
            submitted,
            ScriptedOutcome.Response(response(200, """{"status":"failed","text":"","code":"INVALID_AUDIO"}""")),
        )
        val failed = run(workflowConfig(pollWorkflow), failedTransport, audio, retrying())
        assertTrue(failed is AdvancedAudioClient.Result.Failure)
        assertTrue((failed as AdvancedAudioClient.Result.Failure).placeholderEligible)
        assertEquals(2, failedTransport.requests.size)
    }

    @Test
    fun noWordsDoesNotHidePrepareAuthenticationOrMalformedResponseFailures() = withAudio { audio ->
        val prepare = HttpStage(
            method = HttpMethod.GET,
            url = "https://workflow.test/prepare",
            captures = listOf(Capture("token", ResponseExtractor.JsonPath("$.token"))),
        )
        val cases = listOf(
            asyncRecognition().copy(prepare = prepare) to response(400, """{"message":"ASR_RESPONSE_HAVE_NO_WORDS"}"""),
            asyncRecognition().copy(prepare = prepare) to response(200, """{"token":null}"""),
            requestRecognition() to response(401, """{"message":"ASR_RESPONSE_HAVE_NO_WORDS"}"""),
            requestRecognition() to response(400, """{"message":"invalid audio"}"""),
            requestRecognition() to response(200, "{}"),
            requestRecognition() to response(200, """{"text":[]}"""),
            requestRecognition() to response(200, """{"text":{}}"""),
            requestRecognition() to response(200, "malformed JSON"),
        )
        cases.forEach { (recognition, response) ->
            val transport = FakeTransport(ScriptedOutcome.Response(response))
            val result = run(workflowConfig(recognition), transport, audio, retrying())
            assertTrue("$recognition: $result", result is AdvancedAudioClient.Result.Failure)
            assertTrue((result as AdvancedAudioClient.Result.Failure).placeholderEligible)
            assertEquals(1, transport.requests.size)
        }
    }

    @Test
    fun emptyStreamIsSuccessfulOnlyWithExplicitCompletion() = withAudio { audio ->
        val cases = listOf(
            "event: done\ndata: {}\n\n" to "",
            "data: {\"delta\":\"\"}\n\nevent: done\ndata: {}\n\n" to "",
            "data: {\"delta\":null}\n\nevent: done\ndata: {}\n\n" to "",
            "data: {\"delta\":\" \\t\"}\n\nevent: done\ndata: {}\n\n" to " \t",
        )
        cases.forEach { (body, expected) ->
            val transport = FakeTransport(ScriptedOutcome.Response(response(200, body)))
            val result = run(workflowConfig(streamRecognition()), transport, audio, retrying())
            assertTrue("$body: $result", result is AdvancedAudioClient.Result.Success)
            assertEquals(expected, (result as AdvancedAudioClient.Result.Success).text)
            assertEquals(1, transport.requests.size)
        }
        listOf("", "data: {\"delta\":null}\n\n", "data: {\"delta\":{}}\n\nevent: done\ndata: {}\n\n").forEach { body ->
            val transport = FakeTransport(ScriptedOutcome.Response(response(200, body)))
            val result = run(workflowConfig(streamRecognition()), transport, audio)
            assertTrue("$body: $result", result is AdvancedAudioClient.Result.Failure)
        }
    }

    @Test
    fun streamEmptyFinalOrNoWordsFailureDiscardsEarlierPartialText() = withAudio { audio ->
        val base = streamRecognition()
        val recognition = base.copy(
            stream = base.stream.copy(rules = listOf(
                StreamRule(event = "partial", path = "$.text", action = StreamAction.REPLACE_PARTIAL),
                StreamRule(event = "final", path = "$.text", action = StreamAction.SET_FINAL_TEXT),
                StreamRule(event = "error", path = "$.message", action = StreamAction.FAIL),
                StreamRule(event = "error", action = StreamAction.COMPLETE),
                StreamRule(event = "done", action = StreamAction.COMPLETE),
            )),
        )
        listOf(
            "event: final\ndata: {\"text\":null}\n\nevent: done\ndata: {}\n\n",
            "event: partial\ndata: {\"text\":null}\n\nevent: done\ndata: {}\n\n",
            "event: error\ndata: {\"message\":\"ASR_RESPONSE_HAVE_NO_WORDS\"}\n\n",
            "event: error\ndata: {\"code\":\"ASR_RESPONSE_HAVE_NO_WORDS\",\"message\":\"no speech\"}\n\n",
        ).forEach { ending ->
            val body = "event: partial\ndata: {\"text\":\"discard this partial\"}\n\n$ending"
            val transport = FakeTransport(ScriptedOutcome.Response(response(200, body)))
            val result = run(workflowConfig(recognition), transport, audio, retrying())
            assertTrue("$body: $result", result is AdvancedAudioClient.Result.Success)
            assertEquals("", (result as AdvancedAudioClient.Result.Success).text)
            assertEquals(1, transport.requests.size)
        }
    }

    @Test
    fun noWordsMarkerDoesNotBypassStreamRuleMatching() = withAudio { audio ->
        val base = streamRecognition()
        val recognition = base.copy(
            stream = base.stream.copy(rules = listOf(
                StreamRule(event = "error", path = "$.message", action = StreamAction.FAIL, equals = "fatal"),
                StreamRule(event = "delta", path = "$.text", action = StreamAction.APPEND_DELTA),
                StreamRule(event = "done", action = StreamAction.COMPLETE),
            )),
        )
        val body = "event: error\ndata: {\"code\":\"ASR_RESPONSE_HAVE_NO_WORDS\",\"message\":\"no speech\"}\n\n" +
            "event: delta\ndata: {\"text\":\"kept\"}\n\nevent: done\ndata: {}\n\n"
        val transport = FakeTransport(ScriptedOutcome.Response(response(200, body)))
        val result = run(workflowConfig(recognition), transport, audio, retrying())
        assertTrue(result is AdvancedAudioClient.Result.Success)
        assertEquals("kept", (result as AdvancedAudioClient.Result.Success).text)
    }

    @Test
    fun noWordsStreamCompletionIgnoresSameChunkDoneAndTextFrames() = withAudio { audio ->
        StreamFormat.entries.forEach { format ->
            listOf(
                listOf("""{"done":true}"""),
                listOf("""{"delta":"ignored tail"}"""),
                listOf("""{"done":true}""", """{"delta":"ignored tail"}"""),
            ).forEach { tail ->
                val frames = listOf(
                    """{"delta":"discard this partial"}""",
                    """{"error":"ASR_RESPONSE_HAVE_NO_WORDS"}""",
                ) + tail
                val transport = FakeTransport(
                    ScriptedOutcome.Response(response(200, encodeStreamFrames(format, frames))),
                )
                val result = run(workflowConfig(streamRecognitionWithNoWordsRule(format)), transport, audio, retrying())

                assertTrue("$format $tail: $result", result is AdvancedAudioClient.Result.Success)
                assertEquals("", (result as AdvancedAudioClient.Result.Success).text)
                assertEquals(1, transport.requests.size)
                assertTrue(transport.responses.single().closed)
            }
        }
    }

    @Test
    fun noWordsStreamCompletionClosesWithoutReadingAgainOrRetrying() = withAudio { audio ->
        StreamFormat.entries.forEach { format ->
            val bytes = encodeStreamFrames(format, listOf("""{"error":"ASR_RESPONSE_HAVE_NO_WORDS"}""")).toByteArray()
            val input = object : ByteArrayInputStream(bytes) {
                var reads = 0
                var closed = false

                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    reads += 1
                    if (reads > 1) throw IOException("The server keeps the stream open after no-words completion")
                    return super.read(buffer, offset, length)
                }

                override fun close() {
                    closed = true
                    super.close()
                }
            }
            val transport = FakeTransport(
                ScriptedOutcome.Response(FakeResponse(200, bytes, streamFactory = { input })),
            )
            val result = run(workflowConfig(streamRecognitionWithNoWordsRule(format)), transport, audio, retrying())

            assertTrue("$format: $result", result is AdvancedAudioClient.Result.Success)
            assertEquals("", (result as AdvancedAudioClient.Result.Success).text)
            assertEquals(1, input.reads)
            assertTrue(input.closed)
            assertEquals(1, transport.requests.size)
            assertTrue(transport.responses.single().closed)
        }
    }

    @Test
    fun ordinaryStreamCompletionStillRejectsLaterActions() = withAudio { audio ->
        StreamFormat.entries.forEach { format ->
            listOf(
                """{"delta":"invalid tail"}""",
                """{"done":true}""",
                """{"error":"ASR_RESPONSE_HAVE_NO_WORDS"}""",
            ).forEach { tail ->
                val body = encodeStreamFrames(format, listOf("""{"done":true}""", tail))
                val transport = FakeTransport(ScriptedOutcome.Response(response(200, body)))
                val result = run(workflowConfig(streamRecognitionWithNoWordsRule(format)), transport, audio, retrying())

                assertTrue("$format $tail: $result", result is AdvancedAudioClient.Result.Failure)
                assertTrue((result as AdvancedAudioClient.Result.Failure).placeholderEligible)
                assertEquals(1, transport.requests.size)
                assertTrue(transport.responses.single().closed)
            }
        }
    }

    @Test
    fun asyncSubmitDoesNotRetryNetworkOrServerFailures() = withAudio { audio ->
        listOf<ScriptedOutcome>(
            ScriptedOutcome.Failure(HttpTransportException.NetworkFailure(IOException("offline"))),
            ScriptedOutcome.Response(response(503, "{\"error\":\"unavailable\"}")),
        ).forEach { failure ->
            val transport = FakeTransport(failure)
            val result = run(workflowConfig(asyncRecognition()), transport, audio, retrying())

            assertTrue(result is AdvancedAudioClient.Result.Failure)
            assertTrue((result as AdvancedAudioClient.Result.Failure).placeholderEligible)
            assertEquals(1, transport.requests.count { it.url.endsWith("/submit") })
            assertEquals(1, transport.requests.size)
        }
    }

    @Test
    fun pollRetriesItsOwnNetworkAndServerFailuresWithoutResubmitting() = withAudio { audio ->
        listOf<ScriptedOutcome>(
            ScriptedOutcome.Failure(HttpTransportException.NetworkFailure(IOException("offline"))),
            ScriptedOutcome.Response(response(503, "{\"status\":\"pending\"}")),
        ).forEach { pollFailure ->
            val transport = FakeTransport(
                ScriptedOutcome.Response(response(202, "{\"task\":\"one\"}")),
                pollFailure,
                ScriptedOutcome.Response(response(200, "{\"status\":\"succeeded\",\"text\":\"polled\"}")),
            )
            val result = run(workflowConfig(asyncRecognition(withPoll = true)), transport, audio, retrying())

            assertTrue(result is AdvancedAudioClient.Result.Success)
            assertEquals("polled", (result as AdvancedAudioClient.Result.Success).text)
            assertEquals(1, transport.requests.count { it.url.endsWith("/submit") })
            assertEquals(2, transport.requests.count { it.url.endsWith("/poll") })
        }
    }

    @Test
    fun pollRetryDelayCannotOutrunThePollDeadline() = withAudio { audio ->
        val transport = FakeTransport(
            ScriptedOutcome.Response(response(202, "{\"task\":\"one\"}")),
            ScriptedOutcome.Failure(HttpTransportException.NetworkFailure(IOException("offline"))),
            ScriptedOutcome.Response(response(200, "{\"status\":\"succeeded\",\"text\":\"late\"}")),
        )
        val result = run(
            workflowConfig(asyncRecognition(withPoll = true)),
            transport,
            audio,
            RetryConfig(enabled = true, maxRetries = 1, initialBackoffSeconds = 1.0),
        )

        assertTrue(result is AdvancedAudioClient.Result.Failure)
        assertTrue((result as AdvancedAudioClient.Result.Failure).message.contains("timed out"))
        assertEquals(1, transport.requests.count { it.url.endsWith("/submit") })
        assertEquals(1, transport.requests.count { it.url.endsWith("/poll") })
    }

    @Test
    fun pollResponseThatArrivesAfterItsDeadlineIsNotAccepted() = withAudio { audio ->
        val transport = FakeTransport(
            ScriptedOutcome.Response(response(202, "{\"task\":\"one\"}")),
            ScriptedOutcome.DelayedResponse(
                delayMillis = 125,
                response = response(200, "{\"status\":\"succeeded\",\"text\":\"late\"}"),
            ),
        )
        val result = run(workflowConfig(asyncRecognition(withPoll = true)), transport, audio)

        assertTrue(result is AdvancedAudioClient.Result.Failure)
        assertTrue((result as AdvancedAudioClient.Result.Failure).message.contains("timed out"))
        assertEquals(1, transport.requests.count { it.url.endsWith("/submit") })
        assertEquals(1, transport.requests.count { it.url.endsWith("/poll") })
    }

    @Test
    fun pollPassesItsRemainingDeadlineToTheInFlightTransportCall() = withAudio { audio ->
        val transport = FakeTransport(
            ScriptedOutcome.Response(response(202, "{\"task\":\"one\"}")),
            ScriptedOutcome.DelayedResponse(
                delayMillis = 125,
                response = response(200, "{\"status\":\"succeeded\",\"text\":\"late\"}"),
            ),
        )

        val result = run(workflowConfig(asyncRecognition(withPoll = true)), transport, audio)

        assertTrue(result is AdvancedAudioClient.Result.Failure)
        assertTrue((result as AdvancedAudioClient.Result.Failure).message.contains("timed out"))
        assertEquals(2, transport.callTimeouts.size)
        assertEquals(null, transport.callTimeouts[0])
        assertTrue((transport.callTimeouts[1] ?: 0L) in 1L..100L)
    }

    @Test
    fun capturesAreBoundedPerStageAndRemainAvailableAcrossStages() = withAudio { audio ->
        val captureValue = "x".repeat(200 * 1024)
        val prepare = HttpStage(
            method = HttpMethod.GET,
            url = "https://workflow.test/prepare",
            acceptedStatuses = listOf(200),
            captures = listOf(
                Capture("prepare_first", ResponseExtractor.JsonPath("$.first")),
                Capture("prepare_second", ResponseExtractor.JsonPath("$.second")),
            ),
        )
        val submit = audioSubmitStage("https://workflow.test/submit", acceptedStatuses = listOf(200)).copy(
            captures = listOf(
                Capture("submit_first", ResponseExtractor.JsonPath("$.first")),
                Capture("submit_second", ResponseExtractor.JsonPath("$.second")),
            ),
        )
        val workflow = AsyncPollRecognition(
            prepare = prepare,
            submit = submit,
            finalText = ResponseExtractor.JsonPath("$.text"),
        )
        val transport = FakeTransport(
            ScriptedOutcome.Response(response(200, "{\"first\":\"$captureValue\",\"second\":\"$captureValue\"}")),
            ScriptedOutcome.Response(
                response(200, "{\"first\":\"$captureValue\",\"second\":\"$captureValue\",\"text\":\"complete\"}"),
            ),
        )

        val result = run(workflowConfig(workflow), transport, audio)

        assertTrue(result is AdvancedAudioClient.Result.Success)
        assertEquals("complete", (result as AdvancedAudioClient.Result.Success).text)
        assertEquals(2, transport.requests.size)
    }

    @Test
    fun pollComparisonAndBooleanConditionsFailWhenTheirExtractorIsMissing() = withAudio { audio ->
        listOf(
            PollOperator.EQ to "missing-value",
            PollOperator.NE to "missing-value",
            PollOperator.IS_TRUE to null,
            PollOperator.IS_FALSE to null,
        ).forEach { (operator, value) ->
            val poll = PollStage(
                request = HttpStage(HttpMethod.GET, "https://workflow.test/poll", acceptedStatuses = listOf(200)),
                intervalMs = 100,
                timeoutMs = 100,
                pending = listOf(PollCondition(ResponseExtractor.Status, PollOperator.EQ, "201")),
                success = listOf(PollCondition(ResponseExtractor.Status, PollOperator.EQ, "200")),
                failure = listOf(
                    PollCondition(ResponseExtractor.JsonPath("$.missing"), operator, value),
                ),
            )
            val workflow = AsyncPollRecognition(
                submit = audioSubmitStage("https://workflow.test/submit", acceptedStatuses = listOf(202)),
                poll = poll,
                finalText = ResponseExtractor.JsonPath("$.text"),
            )
            val transport = FakeTransport(
                ScriptedOutcome.Response(response(202, "{\"task\":\"one\"}")),
                ScriptedOutcome.Response(response(200, "{\"text\":\"unexpected success\"}")),
            )

            val result = run(workflowConfig(workflow), transport, audio)

            assertTrue("$operator must fail extraction", result is AdvancedAudioClient.Result.Failure)
            assertEquals(
                "Advanced Audio API response could not be extracted",
                (result as AdvancedAudioClient.Result.Failure).message,
            )
            assertEquals(1, transport.requests.count { it.url.endsWith("/submit") })
            assertEquals(1, transport.requests.count { it.url.endsWith("/poll") })
        }
    }

    @Test
    fun readonlyResultRetriesWithoutResubmitting() = withAudio { audio ->
        val transport = FakeTransport(
            ScriptedOutcome.Response(response(202, "{\"task\":\"one\"}")),
            ScriptedOutcome.Response(response(503, "{\"error\":\"retry\"}")),
            ScriptedOutcome.Response(response(200, "{\"text\":\"result step\"}")),
        )
        val result = run(workflowConfig(asyncRecognition(withResultStep = true)), transport, audio, retrying())

        assertTrue(result is AdvancedAudioClient.Result.Success)
        assertEquals("result step", (result as AdvancedAudioClient.Result.Success).text)
        assertEquals(1, transport.requests.count { it.url.endsWith("/submit") })
        assertEquals(2, transport.requests.count { it.url.endsWith("/result") })
    }

    @Test
    fun streamDoesNotReturnPartialTextBeforeAnExplicitCompleteEvent() = withAudio { audio ->
        val transport = FakeTransport(
            ScriptedOutcome.Response(response(200, "data: {\"delta\":\"partial\"}\n\n")),
        )
        val result = run(workflowConfig(streamRecognition()), transport, audio)

        assertTrue(result is AdvancedAudioClient.Result.Failure)
        assertFalse((result as AdvancedAudioClient.Result.Failure).message.contains("partial"))
    }

    @Test
    fun streamReturnsTextAfterItsExplicitCompleteEvent() = withAudio { audio ->
        val transport = FakeTransport(
            ScriptedOutcome.Response(
                response(
                    200,
                    "data: {\"delta\":\"complete text\"}\n\n" +
                        "event: done\n" +
                        "data: {}\n\n",
                ),
            ),
        )
        val result = run(workflowConfig(streamRecognition()), transport, audio)

        assertTrue(result is AdvancedAudioClient.Result.Success)
        assertEquals("complete text", (result as AdvancedAudioClient.Result.Success).text)
    }

    @Test
    fun remoteDeliveryRejectsMissingRemoteConfigurationBeforeAnyRequest() = withAudio { audio ->
        val transport = FakeTransport()
        val workflow = AdvancedAudioWorkflow(
            schemaVersion = LEGACY_WORKFLOW_SCHEMA_VERSION,
            name = "remote",
            audio = AudioSpec(AudioDeliveryType.PUBLIC_HTTPS_URL, "audio/wav"),
            recognition = RequestRecognition(
                request = HttpStage(
                    method = HttpMethod.POST,
                    url = "https://workflow.test/request",
                    body = HttpBody.Json(
                        JsonValue.Object(linkedMapOf("url" to JsonValue.Text("{{audio:public_url}}"))),
                    ),
                ),
                finalText = ResponseExtractor.JsonPath("$.text"),
            ),
        )

        val result = run(workflowConfig(workflow), transport, audio)

        assertTrue(result is AdvancedAudioClient.Result.Failure)
        assertEquals(0, transport.requests.size)
    }

    @Test
    fun unsafeStaticOrRenderedHeadersAreRejectedBeforeRemoteAudioUpload() = withAudio { audio ->
        val remoteSecrets = mapOf("remote-user" to "user", "remote-password" to "password")
        val remoteConfig = AdvancedRemoteAudioConfig.WebDav(
            uploadBaseUrl = "https://storage.test/upload",
            publicDownloadBaseUrl = "https://download.test/audio",
            usernameSecretId = "remote-user",
            passwordSecretId = "remote-password",
        )
        val recognition = RequestRecognition(
            request = HttpStage(
                method = HttpMethod.POST,
                url = "https://workflow.test/request",
                headers = mapOf("Authorization" to "{{var:token}}"),
                body = HttpBody.Json(
                    JsonValue.Object(linkedMapOf("url" to JsonValue.Text("{{audio:public_url}}"))),
                ),
            ),
            finalText = ResponseExtractor.JsonPath("$.text"),
        )
        val workflow = AdvancedAudioWorkflow(
            schemaVersion = CURRENT_WORKFLOW_SCHEMA_VERSION,
            name = "remote header preflight",
            parameters = listOf(
                ParameterDefinition(
                    id = "token",
                    label = "Token",
                    required = true,
                    parameterType = ParameterType.TEXT,
                ),
            ),
            audio = AudioSpec(AudioDeliveryType.PUBLIC_HTTPS_URL, "audio/wav"),
            recognition = recognition,
        )
        val transport = FakeTransport()
        val result = run(
            config = workflowConfig(workflow).copy(
                values = mapOf("token" to "Bearer valid\r\nX-Injected: true"),
                remoteAudio = remoteConfig,
            ),
            transport = transport,
            audio = audio,
            secrets = remoteSecrets,
        )

        assertTrue(result is AdvancedAudioClient.Result.Failure)
        // The RemoteAudioPublisher uses the same fake transport. A request
        // here would prove that a dynamic header escaped the preflight gate.
        assertEquals(0, transport.requests.size)
    }

    @Test
    fun cancellationAfterRecognitionCompletionForcesRemoteAudioCleanup() = withAudio { audio ->
        val cancellation = AdvancedCancellationSource()
        val transport = FakeTransport(
            ScriptedOutcome.Response(response(201, "")),
            ScriptedOutcome.Response(response(200, "{\"text\":\"ok\"}")),
            ScriptedOutcome.Response(response(204, "")),
        )
        val workflow = AdvancedAudioWorkflow(
            schemaVersion = LEGACY_WORKFLOW_SCHEMA_VERSION,
            name = "remote cleanup cancellation",
            audio = AudioSpec(AudioDeliveryType.PUBLIC_HTTPS_URL, "audio/wav"),
            recognition = RequestRecognition(
                request = HttpStage(
                    method = HttpMethod.POST,
                    url = "https://workflow.test/request",
                    body = HttpBody.Json(
                        JsonValue.Object(linkedMapOf("url" to JsonValue.Text("{{audio:public_url}}"))),
                    ),
                    acceptedStatuses = listOf(200),
                ),
                finalText = ResponseExtractor.JsonPath("$.text"),
            ),
        )
        val config = workflowConfig(workflow).copy(
            remoteAudio = AdvancedRemoteAudioConfig.WebDav(
                uploadBaseUrl = "https://storage.test/upload",
                publicDownloadBaseUrl = "https://download.test/audio",
                usernameSecretId = "remote-user",
                passwordSecretId = "remote-password",
                deleteAfterRecognition = false,
            ),
        )
        val client = AdvancedAudioClient(transport).apply {
            afterRecognitionCompletedBeforeCleanupForTest = { cancellation.cancel() }
        }

        val result = client.transcribe(
            AdvancedAudioClient.Request(
                config = config,
                secrets = mapOf("remote-user" to "user", "remote-password" to "password"),
                audioFile = audio,
                mimeType = "audio/wav",
            ),
            cancellation.token,
        )

        assertTrue(result is AdvancedAudioClient.Result.Cancelled)
        assertEquals(
            listOf(AdvancedHttpMethod.PUT, AdvancedHttpMethod.POST, AdvancedHttpMethod.DELETE),
            transport.requests.map(HttpRequestSpec::method),
        )
    }

    private fun requestRecognition(): RequestRecognition = RequestRecognition(
        request = audioSubmitStage("https://workflow.test/request", acceptedStatuses = listOf(200)),
        finalText = ResponseExtractor.JsonPath("$.text"),
    )

    private fun asyncRecognition(
        withPoll: Boolean = false,
        withResultStep: Boolean = false,
    ): AsyncPollRecognition = AsyncPollRecognition(
        submit = audioSubmitStage("https://workflow.test/submit", acceptedStatuses = listOf(202)),
        poll = if (withPoll) {
            PollStage(
                request = HttpStage(HttpMethod.GET, "https://workflow.test/poll", acceptedStatuses = listOf(200)),
                intervalMs = 100,
                timeoutMs = 100,
                pending = listOf(PollCondition(ResponseExtractor.JsonPath("$.status"), PollOperator.EQ, "pending")),
                success = listOf(PollCondition(ResponseExtractor.JsonPath("$.status"), PollOperator.EQ, "succeeded")),
                failure = listOf(PollCondition(ResponseExtractor.JsonPath("$.status"), PollOperator.EQ, "failed")),
            )
        } else {
            null
        },
        resultSteps = if (withResultStep) {
            listOf(HttpStage(HttpMethod.GET, "https://workflow.test/result", acceptedStatuses = listOf(200)))
        } else {
            emptyList()
        },
        finalText = ResponseExtractor.JsonPath("$.text"),
    )

    private fun streamRecognition(): RequestStreamRecognition = RequestStreamRecognition(
        request = audioSubmitStage("https://workflow.test/stream", acceptedStatuses = listOf(200)),
        stream = StreamResponse(
            format = StreamFormat.SSE,
            rules = listOf(
                StreamRule(path = "$.delta", action = StreamAction.APPEND_DELTA),
                StreamRule(event = "done", action = StreamAction.COMPLETE),
            ),
        ),
    )

    private fun streamRecognitionWithNoWordsRule(format: StreamFormat): RequestStreamRecognition =
        streamRecognition().copy(
            stream = StreamResponse(
                format = format,
                rules = listOf(
                    StreamRule(path = "$.delta", action = StreamAction.APPEND_DELTA),
                    StreamRule(path = "$.error", action = StreamAction.FAIL),
                    StreamRule(path = "$.done", action = StreamAction.COMPLETE, equals = "true"),
                ),
            ),
        )

    private fun encodeStreamFrames(format: StreamFormat, frames: List<String>): String =
        frames.joinToString("") { frame ->
            when (format) {
                StreamFormat.SSE -> "data: $frame\n\n"
                StreamFormat.NDJSON -> "$frame\n"
                StreamFormat.JSON_CHUNKS -> frame
            }
        }

    private fun audioSubmitStage(url: String, acceptedStatuses: List<Int>): HttpStage = HttpStage(
        method = HttpMethod.POST,
        url = url,
        body = HttpBody.Multipart(listOf(MultipartField("file", MultipartValue.AudioFile))),
        acceptedStatuses = acceptedStatuses,
    )

    private fun workflowConfig(recognition: Recognition): AdvancedAudioConfig = workflowConfig(
        AdvancedAudioWorkflow(
            schemaVersion = LEGACY_WORKFLOW_SCHEMA_VERSION,
            name = "test workflow",
            audio = AudioSpec(AudioDeliveryType.MULTIPART_FILE, "audio/wav"),
            recognition = recognition,
        ),
    )

    private fun workflowConfig(workflow: AdvancedAudioWorkflow): AdvancedAudioConfig = AdvancedAudioConfig(
        enabled = true,
        workflowJson = AdvancedAudioWorkflowCodec.encode(workflow),
    )

    private fun run(
        config: AdvancedAudioConfig,
        transport: FakeTransport,
        audio: File,
        retry: RetryConfig = RetryConfig(),
        secrets: Map<String, String> = emptyMap(),
        onDiagnostic: ((HttpDiagnosticEvent) -> Unit)? = null,
    ): AdvancedAudioClient.Result = AdvancedAudioClient(transport).transcribe(
        AdvancedAudioClient.Request(
            config = config,
            secrets = secrets,
            audioFile = audio,
            mimeType = "audio/wav",
            retry = retry,
            onDiagnostic = onDiagnostic,
        ),
    )

    private fun retrying(): RetryConfig = RetryConfig(
        enabled = true,
        maxRetries = 2,
        initialBackoffSeconds = 0.0,
    )

    private fun response(status: Int, body: String): FakeResponse = FakeResponse(status, body.toByteArray())

    private fun withAudio(block: (File) -> Unit) {
        val audio = File.createTempFile("advanced-client-", ".wav").apply {
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }
        try {
            block(audio)
        } finally {
            audio.delete()
        }
    }

    private sealed interface ScriptedOutcome {
        data class Response(val response: FakeResponse) : ScriptedOutcome
        data class DelayedResponse(val delayMillis: Long, val response: FakeResponse) : ScriptedOutcome
        data class Failure(val error: Throwable) : ScriptedOutcome
    }

    private class FakeTransport(vararg planned: ScriptedOutcome) : AdvancedHttpTransport {
        private val planned = planned.toMutableList()
        val requests = mutableListOf<HttpRequestSpec>()
        val callTimeouts = mutableListOf<Long?>()
        val responses = mutableListOf<FakeResponse>()

        override fun execute(
            request: HttpRequestSpec,
            cancellation: AdvancedCancellationToken,
            callTimeoutMillis: Long?,
        ): HttpResponse {
            requests += request
            callTimeouts += callTimeoutMillis
            cancellation.throwIfCancelled()
            return when (val outcome = planned.removeFirstOrNull() ?: error("Unexpected request")) {
                is ScriptedOutcome.Response -> outcome.response.copy()
                is ScriptedOutcome.DelayedResponse -> {
                    Thread.sleep(outcome.delayMillis)
                    outcome.response.copy()
                }
                is ScriptedOutcome.Failure -> throw outcome.error
            }.also(responses::add)
        }
    }

    private class FakeResponse(
        override val statusCode: Int,
        private val bytes: ByteArray,
        override val headers: List<HttpHeader> = emptyList(),
        private val streamFactory: (() -> InputStream)? = null,
    ) : HttpResponse {
        var closed = false
            private set

        override fun readBody(maxBytes: Int): ByteArray {
            check(bytes.size <= maxBytes)
            return bytes.copyOf()
        }

        override fun openBodyStream(): InputStream = streamFactory?.invoke() ?: ByteArrayInputStream(bytes.copyOf())

        override fun close() {
            closed = true
        }

        fun copy(): FakeResponse = FakeResponse(statusCode, bytes.copyOf(), headers.toList(), streamFactory)
    }
}
