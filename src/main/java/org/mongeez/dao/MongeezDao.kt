/*
 * Copyright 2011 SecondMarket Labs, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */

package org.mongeez.dao

import com.mongodb.MongoClient
import com.mongodb.MongoClientOptions
import com.mongodb.ServerAddress
import com.mongodb.client.MongoCollection
import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Filters.eq
import com.mongodb.client.model.Filters.exists
import com.mongodb.client.model.Indexes.ascending
import com.mongodb.client.model.Updates.set
import org.apache.commons.lang3.time.DateFormatUtils.ISO_DATETIME_TIME_ZONE_FORMAT
import org.bson.Document
import org.mongeez.MongoAuth
import org.mongeez.commands.ChangeSet
import org.mongeez.dao.shell.MongoShellRunner

class MongeezDao(serverAddress: ServerAddress,
                 databaseName: String,
                 auth: MongoAuth? = null,
                 private val useMongoShell: Boolean = false) {

    private val db: MongoDatabase
    private val mongoShellRunner: MongoShellRunner
    private var changeSetAttributes = emptyList<ChangeSetAttribute>()

    private val mongeezCollection: MongoCollection<Document>
        get() = db.getCollection("mongeez")

    init {
        val credential = auth?.getCredential(databaseName)
        val clientOptions = MongoClientOptions.builder().build()
        val mongoClient = credential
                ?.let { MongoClient(serverAddress, it, clientOptions) }
                ?: MongoClient(serverAddress, clientOptions)
        db = mongoClient.getDatabase(databaseName)
        mongoShellRunner = MongoShellRunner(serverAddress, databaseName, credential)
        configure()
    }

    private fun configure() {
        addTypeToUntypedRecords()
        loadConfigurationRecord()
        dropObsoleteChangeSetExecutionIndices()
        ensureChangeSetExecutionIndex()
    }

    private fun addTypeToUntypedRecords() {
        val query = exists("type", false)
        val update = set("type", RecordType.CHANGE_SET_EXECUTION.dbVal)
        mongeezCollection.updateMany(query, update)
    }

    private fun loadConfigurationRecord() {
        val query = eq("type", RecordType.CONFIGURATION.dbVal)
        val configRecord = mongeezCollection.find(query).first() ?: createNewConfigRecord()
        val supportResourcePath = configRecord.getBoolean("supportResourcePath")
        changeSetAttributes =
                if (supportResourcePath) {
                    DEFAULT_CHANGE_SET_ATTRIBUTES + ChangeSetAttribute.RESOURCE_PATH
                } else {
                    DEFAULT_CHANGE_SET_ATTRIBUTES
                }
    }

    private fun createNewConfigRecord(): Document {
        val configRecord = if (mongeezCollection.countDocuments() > 0L) {
            // We have pre-existing records, so don't assume that they support the latest features
            Document()
                    .append("type", RecordType.CONFIGURATION.dbVal)
                    .append("supportResourcePath", false)
        } else {
            Document()
                    .append("type", RecordType.CONFIGURATION.dbVal)
                    .append("supportResourcePath", true)
        }
        mongeezCollection.insertOne(configRecord)
        return configRecord
    }

    /**
     * Removes indices that were generated by versions before 0.9.3, since they're not supported by MongoDB 2.4+
     */
    private fun dropObsoleteChangeSetExecutionIndices() {
        val indexName = "type_changeSetExecution_file_1_changeId_1_author_1_resourcePath_1"
        val collection = mongeezCollection
        collection.listIndexes()
                .filter { indexName == it.getString("name") }
                .forEach { collection.dropIndex(it) }
    }

    private fun ensureChangeSetExecutionIndex() {
        val attributeNames = listOf("type") + changeSetAttributes.map { it.dbFieldName }
        mongeezCollection.createIndex(ascending(attributeNames))
    }

    fun wasExecuted(changeSet: ChangeSet): Boolean {
        val query = Document("type", RecordType.CHANGE_SET_EXECUTION.dbVal)
        for (attribute in changeSetAttributes) {
            query.append(attribute.dbFieldName, attribute.getAttributeValue(changeSet))
        }
        return mongeezCollection.countDocuments(query) > 0
    }

    fun runScript(code: String, util: String?) {
        val theCode = util.getTheCode(code)
        if (useMongoShell) {
            mongoShellRunner.run(theCode)
        } else {
            val command = Document("eval", theCode)
            db.runCommand(command)
        }
    }

    fun logChangeSet(changeSet: ChangeSet) {
        val dbObject = Document("type", RecordType.CHANGE_SET_EXECUTION.dbVal)
        for (attribute in changeSetAttributes) {
            dbObject.append(attribute.dbFieldName, attribute.getAttributeValue(changeSet))
        }
        dbObject.append("date", ISO_DATETIME_TIME_ZONE_FORMAT.format(System.currentTimeMillis()))
        mongeezCollection.insertOne(dbObject)
    }

    private companion object {
        val DEFAULT_CHANGE_SET_ATTRIBUTES = listOf(ChangeSetAttribute.FILE, ChangeSetAttribute.CHANGE_ID, ChangeSetAttribute.AUTHOR)

        fun String?.getTheCode(code: String): String {
            return if (this == null) {
                code
            } else {
                "$this\n$code"
            }
        }
    }
}
