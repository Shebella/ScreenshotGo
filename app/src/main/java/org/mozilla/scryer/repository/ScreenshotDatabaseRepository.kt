/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.scryer.repository

import android.arch.lifecycle.LiveData
import android.arch.lifecycle.MutableLiveData
import android.arch.lifecycle.Transformations
import org.mozilla.scryer.persistence.CollectionModel
import org.mozilla.scryer.persistence.ScreenshotDatabase
import org.mozilla.scryer.persistence.ScreenshotModel
import java.util.concurrent.Executors

class ScreenshotDatabaseRepository(private val database: ScreenshotDatabase) : ScreenshotRepository {
    private val executor = Executors.newSingleThreadExecutor()

    private var collectionListData = database.collectionDao().getCollections()
    private val screenshotListData = database.screenshotDao().getScreenshots()

    override fun addScreenshot(screenshot: ScreenshotModel) {
        executor.submit {
            database.screenshotDao().addScreenshot(screenshot)
        }
    }

    override fun updateScreenshot(screenshot: ScreenshotModel) {
        executor.submit {
            database.screenshotDao().updateScreenshot(screenshot)
        }
    }

    override fun getScreenshots(collectionId: String): LiveData<List<ScreenshotModel>> {
        return database.screenshotDao().getScreenshots(collectionId)
    }

    override fun getScreenshots(): LiveData<List<ScreenshotModel>> {
        return screenshotListData
    }

    override fun getCollections(): LiveData<List<CollectionModel>> {
        return collectionListData
    }

    override fun addCollection(collection: CollectionModel) {
        executor.submit {
            database.collectionDao().addCollection(collection)
        }
    }

    override fun getCollectionCovers(): LiveData<Map<String, ScreenshotModel>> {
        return Transformations.switchMap(database.screenshotDao().getCollectionCovers()) { models ->
            MutableLiveData<Map<String, ScreenshotModel>>().apply {
                value = models.map { it.collectionId to it }.toMap()
            }
        }
    }

    override fun setupDefaultContent() {
        val none = CollectionModel("Unsorted", 0, CollectionModel.CATEGORY_NONE)
        val music = CollectionModel("Music", System.currentTimeMillis())
        val shopping = CollectionModel("Shopping", System.currentTimeMillis())
        val secret = CollectionModel("Secret", System.currentTimeMillis())
        addCollection(none)
        addCollection(music)
        addCollection(shopping)
        addCollection(secret)
    }
}