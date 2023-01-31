package com.deniscerri.ytdlnis.database.viewmodel

import android.app.Activity
import android.app.Application
import android.content.SharedPreferences
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.deniscerri.ytdlnis.App
import com.deniscerri.ytdlnis.R
import com.deniscerri.ytdlnis.database.DBManager
import com.deniscerri.ytdlnis.database.models.DownloadItem
import com.deniscerri.ytdlnis.database.models.Format
import com.deniscerri.ytdlnis.database.models.ResultItem
import com.deniscerri.ytdlnis.database.repository.DownloadRepository
import com.deniscerri.ytdlnis.work.DownloadWorker
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class DownloadViewModel(application: Application) : AndroidViewModel(application) {
    private val repository : DownloadRepository
    private val sharedPreferences: SharedPreferences
    val allDownloads : LiveData<List<DownloadItem>>
    val queuedDownloads : LiveData<List<DownloadItem>>
    val activeDownloads : LiveData<List<DownloadItem>>
    val processingDownloads : LiveData<List<DownloadItem>>

    private var bestVideoFormat: Format

    init {
        val dao = DBManager.getInstance(application).downloadDao
        repository = DownloadRepository(dao)
        sharedPreferences =
            getApplication<App>().getSharedPreferences("root_preferences", Activity.MODE_PRIVATE)

        allDownloads = repository.allDownloads
        queuedDownloads = repository.queuedDownloads
        activeDownloads = repository.activeDownloads
        processingDownloads = repository.processingDownloads

        val format = getApplication<App>().resources.getStringArray(R.array.video_formats)
        val container = sharedPreferences.getString("video_format", "Default")
        bestVideoFormat = Format(
            format[format.lastIndex],
            container!!,
            0,
            format[format.lastIndex]
        )
    }

    fun startWork(items: List<DownloadItem>){
        items.forEach {
            insertDownload(it)
        }

    }

    fun insertDownload(item: DownloadItem) : LiveData<Long> {
        val result = MutableLiveData<Long>()
        viewModelScope.launch(Dispatchers.IO){
            val id = repository.insert(item)
            result.postValue(id)
        }
        return result
    }

    fun deleteDownload(item: DownloadItem) = viewModelScope.launch(Dispatchers.IO) {
        repository.delete(item)
    }

    fun updateDownload(item: DownloadItem) = viewModelScope.launch(Dispatchers.IO){
        repository.update(item);
    }

    fun getItemByID(id: Long) : DownloadItem {
        return repository.getItemByID(id)
    }

    fun createDownloadItemFromResult(resultItem: ResultItem, type: String) : DownloadItem {
         val embedSubs = sharedPreferences.getBoolean("embed_subtitles", false)
        val addChapters = sharedPreferences.getBoolean("add_chapters", false)
        val saveThumb = sharedPreferences.getBoolean("write_thumbnail", false)

        return DownloadItem(0,
            resultItem.url,
            resultItem.title,
            resultItem.author,
            resultItem.thumb,
            resultItem.duration,
            type,
            getFormat(resultItem, type),   false,
            "", resultItem.website, "", resultItem.playlistTitle, embedSubs, addChapters, saveThumb, DownloadRepository.Status.Processing.toString()
        )

    }

    private fun getFormat(resultItem: ResultItem, type: String) : Format {
        when(type) {
            "audio" -> {
                return try {
                    resultItem.formats.last { it.format_note.contains("audio", ignoreCase = true) }
                }catch (e: Exception){
                    Format()
                }
            }
            "video" -> {
                return try {
                    resultItem.formats[resultItem.formats.lastIndex]
                }catch (e: Exception){
                    bestVideoFormat
                }
            }
            else -> {
                return Format()
            }
        }
    }

    fun turnResultItemstoDownloadItems(items: List<ResultItem?>) : List<DownloadItem> {
        val list : MutableList<DownloadItem> = mutableListOf()
        items.forEach {
            list.add(createDownloadItemFromResult(it!!, "video"))
        }
        return list
    }

    fun putDownloadsForProcessing(items: List<ResultItem?>, downloadItems: List<DownloadItem>) : LiveData<List<Long>> {
        val result = MutableLiveData<List<Long>>()
        viewModelScope.launch(Dispatchers.IO){
            val list : MutableList<Long> = mutableListOf()
            items.forEachIndexed { i, it ->
                val tmpDownloadItem = downloadItems[i]
                try {
                    val item = repository.checkIfPresent(it!!)
                    tmpDownloadItem.id = item.id
                    tmpDownloadItem.status = DownloadRepository.Status.Processing.toString()
                    repository.update(tmpDownloadItem)
                    list.add(tmpDownloadItem.id)
                }catch (e: Exception){
                    val id = repository.insert(tmpDownloadItem)
                    list.add(id)
                }

            }
            result.postValue(list)
        }
        return result
    }

    fun deleteSingleProcessing(item: DownloadItem) = viewModelScope.launch(Dispatchers.IO) {
        repository.deleteSingleProcessing(item)
    }

    fun deleteProcessing() = viewModelScope.launch(Dispatchers.IO) {
        repository.deleteProcessing()
    }

    fun cloneDownloadItem(item: DownloadItem) : DownloadItem {
        val string = Gson().toJson(item, DownloadItem::class.java)
        return Gson().fromJson(string, DownloadItem::class.java)
    }

    fun queueDownloads(items: List<DownloadItem>)= viewModelScope.launch(Dispatchers.IO) {
        val context = getApplication<App>().applicationContext
        items.forEach {
            it.status = DownloadRepository.Status.Queued.toString()
            repository.update(it)

            val workRequest = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setInputData(Data.Builder().putLong("id", it.id).build())
                .addTag("download")
                .build()
            WorkManager.getInstance(context).beginUniqueWork(
                it.id.toString(),
                ExistingWorkPolicy.KEEP,
                workRequest
            ).enqueue()
        }
    }

}