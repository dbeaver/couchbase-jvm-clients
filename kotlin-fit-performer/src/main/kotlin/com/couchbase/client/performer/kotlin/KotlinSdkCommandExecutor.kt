/*
 * Copyright (c) 2022 Couchbase, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.couchbase.client.performer.kotlin

import com.couchbase.client.core.error.CouchbaseException
import com.couchbase.client.kotlin.CommonOptions
import com.couchbase.client.kotlin.codec.JacksonJsonSerializer
import com.couchbase.client.kotlin.codec.JsonTranscoder
import com.couchbase.client.kotlin.codec.RawBinaryTranscoder
import com.couchbase.client.kotlin.codec.RawJsonTranscoder
import com.couchbase.client.kotlin.codec.RawStringTranscoder
import com.couchbase.client.kotlin.kv.Durability
import com.couchbase.client.kotlin.kv.Expiry
import com.couchbase.client.kotlin.kv.GetResult
import com.couchbase.client.kotlin.kv.MutationResult
import com.couchbase.client.kotlin.kv.PersistTo
import com.couchbase.client.kotlin.kv.ReplicateTo
import com.couchbase.client.performer.core.commands.SdkCommandExecutor
import com.couchbase.client.performer.core.perf.Counters
import com.couchbase.client.performer.core.perf.PerRun
import com.couchbase.client.performer.core.util.ErrorUtil
import com.couchbase.client.performer.core.util.TimeUtil
import com.couchbase.client.performer.kotlin.util.ClusterConnection
import com.couchbase.client.protocol.shared.CouchbaseExceptionEx
import com.couchbase.client.protocol.shared.Exception
import com.couchbase.client.protocol.shared.ExceptionOther
import com.couchbase.client.protocol.shared.MutationToken
import com.couchbase.client.protocol.shared.Transcoder
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.jacksonTypeRef
import com.fasterxml.jackson.module.kotlin.jsonMapper
import com.google.protobuf.ByteString
import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import com.couchbase.client.protocol.run.Result as FitRunResult
import com.couchbase.client.protocol.sdk.Command as FitSdkCommand
import com.couchbase.client.protocol.sdk.kv.GetResult as FitGetResult
import com.couchbase.client.protocol.sdk.kv.MutationResult as FitMutationResult
import com.couchbase.client.protocol.shared.Content as FitContent
import com.couchbase.client.protocol.shared.Durability as FitDurability
import com.couchbase.client.protocol.shared.Expiry as FitExpiry
import com.couchbase.client.protocol.shared.PersistTo as FitPersistTo
import com.couchbase.client.protocol.shared.ReplicateTo as FitReplicateTo

/**
 * Performs each requested SDK operation
 */
class KotlinSdkCommandExecutor(
    private val connection: ClusterConnection,
    counters: Counters,
) : SdkCommandExecutor(counters) {

    fun createCommon(hasTimeout: Boolean, timeout: Int): CommonOptions {
        return if (hasTimeout) CommonOptions(timeout = timeout.milliseconds)
        else CommonOptions.Default
    }

    fun convertDurability(hasDurability: Boolean, durability: com.couchbase.client.protocol.shared.DurabilityType): Durability {
        if (!hasDurability) return Durability.None

        if (durability.hasDurabilityLevel()) {
            return when (durability.durabilityLevel) {
                FitDurability.NONE -> Durability.none()
                FitDurability.MAJORITY -> Durability.majority()
                FitDurability.MAJORITY_AND_PERSIST_TO_ACTIVE -> Durability.majorityAndPersistToActive()
                FitDurability.PERSIST_TO_MAJORITY -> Durability.persistToMajority()
                else -> throw UnsupportedOperationException("Unknown durability: $durability")
            }
        }

        if (durability.hasObserve()) {
            return Durability.clientVerified(
                persistTo = when (durability.observe.persistTo) {
                    FitPersistTo.PERSIST_TO_NONE -> PersistTo.NONE
                    FitPersistTo.PERSIST_TO_ACTIVE -> PersistTo.ACTIVE
                    FitPersistTo.PERSIST_TO_ONE -> PersistTo.ONE
                    FitPersistTo.PERSIST_TO_TWO -> PersistTo.TWO
                    FitPersistTo.PERSIST_TO_THREE -> PersistTo.THREE
                    FitPersistTo.PERSIST_TO_FOUR -> PersistTo.FOUR
                    else -> throw UnsupportedOperationException("Unknown durability: $durability")
                },
                replicateTo = when (durability.observe.replicateTo) {
                    FitReplicateTo.REPLICATE_TO_NONE -> ReplicateTo.NONE
                    FitReplicateTo.REPLICATE_TO_ONE -> ReplicateTo.ONE
                    FitReplicateTo.REPLICATE_TO_TWO -> ReplicateTo.TWO
                    FitReplicateTo.REPLICATE_TO_THREE -> ReplicateTo.THREE
                    else -> throw UnsupportedOperationException("Unknown durability: $durability")
                },
            )
        }

        throw UnsupportedOperationException("Unknown durability")
    }

    fun convertExpiry(hasExpiry: Boolean, expiry: FitExpiry): Expiry = when {
        !hasExpiry -> Expiry.none()
        expiry.hasAbsoluteEpochSecs() -> Expiry.of(Instant.ofEpochSecond(expiry.absoluteEpochSecs))
        expiry.hasRelativeSecs() -> Expiry.of(expiry.relativeSecs.seconds)
        else -> throw UnsupportedOperationException("Unknown expiry: $expiry")
    }

    override fun performOperation(op: FitSdkCommand, perRun: PerRun): FitRunResult {
        val result = FitRunResult.newBuilder()

        runBlocking {
            if (op.hasInsert()) {
                val request = op.insert
                val collection = connection.collection(request.location)
                val content = content(request.content)
                val docId = getDocId(request.location)
                result.initiated = TimeUtil.getTimeNow()
                val start = System.nanoTime()
                val r = if (request.hasOptions()) {
                    val options = request.options
                    collection.insert(
                        docId, content,
                        common = createCommon(options.hasTimeoutMsecs(), options.timeoutMsecs),
                        transcoder = convertTranscoder(options.hasTranscoder(), options.transcoder),
                        durability = convertDurability(options.hasDurability(), options.durability),
                        expiry = convertExpiry(options.hasExpiry(), options.expiry),
                    )
                } else collection.insert(docId, content)
                result.elapsedNanos = System.nanoTime() - start
                if (op.returnResult) populateResult(result, r)
                else setSuccess(result)
            } else if (op.hasGet()) {
                val request = op.get
                val collection = connection.collection(request.location)
                val docId = getDocId(request.location)
                result.initiated = TimeUtil.getTimeNow()
                val start = System.nanoTime()
                val r = if (request.hasOptions()) {
                    if (request.options.hasTranscoder()) {
                        // Kotlin does not have this
                        throw UnsupportedOperationException("Unknown transcoder")
                    }
                    val options = request.options
                    collection.get(
                        docId,
                        common = createCommon(options.hasTimeoutMsecs(), options.timeoutMsecs),
                        withExpiry = if (options.hasWithExpiry()) options.hasWithExpiry() else false,
                        project = options.projectionList.toList(),
                    )
                } else collection.get(docId)
                result.elapsedNanos = System.nanoTime() - start
                if (op.returnResult) populateResult(result, r)
                else setSuccess(result)
            } else if (op.hasRemove()) {
                val request = op.remove
                val collection = connection.collection(request.location)
                val docId = getDocId(request.location)
                result.initiated = TimeUtil.getTimeNow()
                val start = System.nanoTime()
                val r = if (request.hasOptions()) {
                    val options = request.options
                    collection.remove(
                        docId,
                        common = createCommon(options.hasTimeoutMsecs(), options.timeoutMsecs),
                        durability = convertDurability(options.hasDurability(), options.durability),
                        cas = if (options.hasCas()) options.cas else 0,
                    )
                } else collection.remove(docId)
                result.elapsedNanos = System.nanoTime() - start
                if (op.returnResult) populateResult(result, r)
                else setSuccess(result)
            } else if (op.hasReplace()) {
                val request = op.replace
                val collection = connection.collection(request.location)
                val docId = getDocId(request.location)
                val content = content(request.content)
                result.initiated = TimeUtil.getTimeNow()
                val start = System.nanoTime()
                val r = if (request.hasOptions()) {
                    val options = request.options
                    collection.replace(
                        docId, content,
                        common = createCommon(options.hasTimeoutMsecs(), options.timeoutMsecs),
                        transcoder = convertTranscoder(options.hasTranscoder(), options.transcoder),
                        durability = convertDurability(options.hasDurability(), options.durability),
                        expiry = convertExpiry(options.hasExpiry(), options.expiry),
                        preserveExpiry = if (options.hasPreserveExpiry()) options.preserveExpiry else false,
                        cas = if (options.hasCas()) options.cas else 0,
                    )
                } else collection.replace(docId, content)
                result.elapsedNanos = System.nanoTime() - start
                if (op.returnResult) populateResult(result, r)
                else setSuccess(result)
            } else if (op.hasUpsert()) {
                val request = op.upsert
                val collection = connection.collection(request.location)
                val docId = getDocId(request.location)
                val content = content(request.content)
                result.initiated = TimeUtil.getTimeNow()
                val start = System.nanoTime()
                val r = if (request.hasOptions()) {
                    val options = request.options
                    collection.upsert(
                        docId, content,
                        common = createCommon(options.hasTimeoutMsecs(), options.timeoutMsecs),
                        transcoder = convertTranscoder(options.hasTranscoder(), options.transcoder),
                        durability = convertDurability(options.hasDurability(), options.durability),
                        expiry = convertExpiry(options.hasExpiry(), options.expiry),
                        preserveExpiry = if (options.hasPreserveExpiry()) options.preserveExpiry else false,
                    )
                } else collection.upsert(docId, content)
                result.elapsedNanos = System.nanoTime() - start
                if (op.returnResult) populateResult(result, r)
                else setSuccess(result)
            } else {
                throw UnsupportedOperationException(IllegalArgumentException("Unknown operation"))
            }
        }

        return result.build()
    }

    override fun convertException(raw: Throwable): Exception = convertExceptionKt(raw)

    fun content(content: FitContent): Any? {
        return when {
            content.hasPassthroughString() -> content.passthroughString

            content.hasConvertToJson() -> jsonMapper.readValue(
                content.convertToJson.toByteArray(),
                jacksonTypeRef<Map<String, Any?>>(),
            )

            else -> throw UnsupportedOperationException("Unknown content: $content")
        }
    }

    private fun convertTranscoder(hasTranscoder: Boolean, transcoderMaybe: Transcoder?): com.couchbase.client.kotlin.codec.Transcoder? {
        if (!hasTranscoder) return null

        val transcoder = transcoderMaybe!!
        return when {
            transcoder.hasRawJson() -> RawJsonTranscoder
            transcoder.hasJson() -> jsonTranscoder
            transcoder.hasRawString() -> RawStringTranscoder
            transcoder.hasRawBinary() -> RawBinaryTranscoder
            // Kotlin does not have LegacyTranscoder
            else -> throw UnsupportedOperationException("Unknown transcoder: $transcoder")
        }
    }

    private fun setSuccess(result: FitRunResult.Builder) {
        result.setSdk(
            com.couchbase.client.protocol.sdk.Result.newBuilder()
                .setSuccess(true)
        )
    }

    private fun populateResult(
        result: FitRunResult.Builder,
        value: MutationResult
    ) {
        val builder = FitMutationResult.newBuilder()
            .setCas(value.cas)
        if (value.mutationToken != null) {
            val mt = value.mutationToken!!
            builder.setMutationToken(
                MutationToken.newBuilder()
                    .setPartitionId(mt.partitionID().toInt())
                    .setPartitionUuid(mt.partitionUUID())
                    .setSequenceNumber(mt.sequenceNumber())
                    .setBucketName(mt.bucketName())
            )
        }
        result.setSdk(
            com.couchbase.client.protocol.sdk.Result.newBuilder()
                .setMutationResult(builder)
        )
    }

    private fun populateResult(result: FitRunResult.Builder, value: GetResult) {
        val builder = FitGetResult.newBuilder()
            .setCas(value.cas)
            .setContent(ByteString.copyFrom(value.content.bytes))
        when (val expiry = value.expiry) {
            is Expiry.Absolute -> builder.expiryTime = expiry.instant.epochSecond
            else -> {}
        }
        result.setSdk(
            com.couchbase.client.protocol.sdk.Result.newBuilder()
                .setGetResult(builder)
        )
    }

    companion object {
        val jsonMapper = jsonMapper {
            addModule(Jdk8Module())
            addModule(KotlinModule.Builder().build())
        }

        val jsonTranscoder = JsonTranscoder(JacksonJsonSerializer(jsonMapper))
    }
}

fun convertExceptionKt(raw: Throwable): Exception {
    if (raw is CouchbaseException || raw is UnsupportedOperationException) {
        val out = CouchbaseExceptionEx.newBuilder()
            .setName(raw.javaClass.simpleName)
            .setType(ErrorUtil.convertException(raw))
            .setSerialized(raw.toString())
        raw.cause?.let { out.cause = convertExceptionKt(it) }

        return Exception.newBuilder()
            .setCouchbase(out)
            .build()
    }

    return Exception.newBuilder()
        .setOther(
            ExceptionOther.newBuilder()
                .setName(raw.javaClass.simpleName)
                .setSerialized(raw.toString())
        ).build()
}
