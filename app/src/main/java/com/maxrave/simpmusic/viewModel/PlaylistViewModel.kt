package com.maxrave.simpmusic.viewModel

import android.app.Application
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import com.maxrave.simpmusic.common.DownloadState
import com.maxrave.simpmusic.common.SELECTED_LANGUAGE
import com.maxrave.simpmusic.data.dataStore.DataStoreManager
import com.maxrave.simpmusic.data.db.entities.LocalPlaylistEntity
import com.maxrave.simpmusic.data.db.entities.PlaylistEntity
import com.maxrave.simpmusic.data.db.entities.SongEntity
import com.maxrave.simpmusic.data.model.browse.album.Track
import com.maxrave.simpmusic.data.model.browse.playlist.PlaylistBrowse
import com.maxrave.simpmusic.data.repository.MainRepository
import com.maxrave.simpmusic.service.test.download.DownloadUtils
import com.maxrave.simpmusic.utils.Resource
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class PlaylistViewModel @Inject constructor(
    private val mainRepository: MainRepository,
    @ApplicationContext private val context: Context,
    application: Application,
    private var dataStoreManager: DataStoreManager
): AndroidViewModel(application) {
    @Inject
    lateinit var downloadUtils: DownloadUtils

    var gradientDrawable: MutableLiveData<GradientDrawable> = MutableLiveData()
    var loading = MutableLiveData<Boolean>()

    private val _playlistBrowse: MutableLiveData<Resource<PlaylistBrowse>?> = MutableLiveData()
    var playlistBrowse: LiveData<Resource<PlaylistBrowse>?> = _playlistBrowse

    private val _id: MutableLiveData<String> = MutableLiveData()
    var id: LiveData<String> = _id

    private var _playlistEntity: MutableLiveData<PlaylistEntity> = MutableLiveData()
    var playlistEntity: LiveData<PlaylistEntity> = _playlistEntity

    private var _liked: MutableStateFlow<Boolean> = MutableStateFlow(false)
    var liked: MutableStateFlow<Boolean> = _liked


    private var _songEntity: MutableLiveData<SongEntity> = MutableLiveData()
    val songEntity: LiveData<SongEntity> = _songEntity
    private var _listLocalPlaylist: MutableLiveData<List<LocalPlaylistEntity>> = MutableLiveData()
    val listLocalPlaylist: LiveData<List<LocalPlaylistEntity>> = _listLocalPlaylist

    private var _prevPlaylistDownloading: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val prevPlaylistDownloading: StateFlow<Boolean> = _prevPlaylistDownloading

    private var regionCode: String? = null
    private var language: String? = null
    init {
        regionCode = runBlocking { dataStoreManager.location.first() }
        language = runBlocking { dataStoreManager.getString(SELECTED_LANGUAGE).first() }
    }

    fun updateId(id: String){
        _id.value = id
    }

    fun browsePlaylist(id: String) {
        loading.value = true
        viewModelScope.launch {
//            mainRepository.browsePlaylist(id, regionCode!!, SUPPORTED_LANGUAGE.serverCodes[SUPPORTED_LANGUAGE.codes.indexOf(language!!)]).collect{ values ->
//                _playlistBrowse.value = values
//            }
            mainRepository.getPlaylistData(id).collect {
                _playlistBrowse.value = it
            }
            withContext(Dispatchers.Main){
                loading.value = false
            }
        }
    }

    fun insertPlaylist(playlistEntity: PlaylistEntity){
        viewModelScope.launch {
            mainRepository.insertPlaylist(playlistEntity)
            mainRepository.getPlaylist(playlistEntity.id).collect{ values ->
                _playlistEntity.value = values
                if (values != null) {
                    _liked.value = values.liked
                }
            }
        }
    }

    fun getPlaylist(id: String){
        viewModelScope.launch {
            mainRepository.getPlaylist(id).collect{ values ->
                if (values != null) {
                    _liked.value = values.liked
                }
                val list = values?.tracks
                var count = 0
                list?.forEach { track ->
                    mainRepository.getSongById(track).collect { song ->
                        if (song != null) {
                            if (song.downloadState == DownloadState.STATE_DOWNLOADED) {
                                count++
                            }
                        }
                    }
                }
                if (count == list?.size) {
                    updatePlaylistDownloadState(id, DownloadState.STATE_DOWNLOADED)
                }
                else {
                    updatePlaylistDownloadState(id, DownloadState.STATE_NOT_DOWNLOADED)
                }
                mainRepository.getPlaylist(id).collect { playlist ->
                    _playlistEntity.value = playlist
                }
            }
        }
    }
    private var _listTrack: MutableLiveData<List<SongEntity>> = MutableLiveData()
    var listTrack: LiveData<List<SongEntity>> = _listTrack

    fun getListTrack(tracks: List<String>?) {
        viewModelScope.launch {
            mainRepository.getSongsByListVideoId(tracks!!).collect { values ->
                _listTrack.value = values
            }
        }
    }

    fun checkAllSongDownloaded(list: ArrayList<Track>) {
        viewModelScope.launch {
            var count = 0
            list.forEach { track ->
                mainRepository.getSongById(track.videoId).collect { song ->
                    if (song != null) {
                        if (song.downloadState == DownloadState.STATE_DOWNLOADED) {
                            count++
                        }
                    }
                }
            }
            if (count == list.size) {
                updatePlaylistDownloadState(id.value!!, DownloadState.STATE_DOWNLOADED)
            }
            mainRepository.getPlaylist(id.value!!).collect { album ->
                if (album != null) {
                    if (playlistEntity.value?.downloadState != album.downloadState) {
                        _playlistEntity.value = album
                    }
                }
            }
        }
    }


    fun updatePlaylistLiked(liked: Boolean, id: String){
        viewModelScope.launch {
            val tempLiked = if(liked) 1 else 0
            mainRepository.updatePlaylistLiked(id, tempLiked)
            mainRepository.getPlaylist(id).collect{ values ->
                _playlistEntity.value = values
                if (values != null) {
                    _liked.value = values.liked
                }
            }
        }
    }
    val listJob: MutableStateFlow<ArrayList<SongEntity>> = MutableStateFlow(arrayListOf())
    val playlistDownloadState: MutableStateFlow<Int> = MutableStateFlow(DownloadState.STATE_NOT_DOWNLOADED)


    fun updatePlaylistDownloadState(id: String, state: Int) {
        viewModelScope.launch {
            mainRepository.getPlaylist(id).collect { playlist ->
                _playlistEntity.value = playlist
                mainRepository.updatePlaylistDownloadState(id, state)
                playlistDownloadState.value = state
            }
        }
    }
//    fun downloading() {
//        _prevPlaylistDownloading.value = true
//    }
//    fun collectDownloadState(id: String) {
//        viewModelScope.launch {
//            listJob.collect { jobs->
//                    Log.w("PlaylistFragment", "ListJob: $jobs")
//                    if (jobs.isNotEmpty()){
//                        var count = 0
//                        jobs.forEach { job ->
//                            if (job.downloadState == DownloadState.STATE_DOWNLOADED) {
//                                count++
//                            }
//                            else if (job.downloadState == DownloadState.STATE_NOT_DOWNLOADED) {
//                                updatePlaylistDownloadState(id, DownloadState.STATE_NOT_DOWNLOADED)
//                                _prevPlaylistDownloading.value = false
//                            }
//                        }
//                        if (count == jobs.size) {
//                            updatePlaylistDownloadState(
//                                id,
//                                DownloadState.STATE_DOWNLOADED
//                            )
//                            _prevPlaylistDownloading.value = false
//                            Toast.makeText(
//                                context,
//                                context.getString(R.string.downloaded),
//                                Toast.LENGTH_SHORT
//                            ).show()
//                        }
//                    }
//            }
//        }
//    }

    @UnstableApi
    fun getDownloadStateFromService(videoId: String) {
        viewModelScope.launch {
            val downloadState = downloadUtils.getDownload(videoId).stateIn(viewModelScope)
            downloadState.collect { down ->
                Log.d("Check Downloaded", "$videoId ${down?.state}")
                if (down != null) {
                    when (down.state) {
                        Download.STATE_COMPLETED -> {
                            mainRepository.getSongById(videoId).collect{ song ->
                                if (song?.downloadState != DownloadState.STATE_DOWNLOADED) {
                                    mainRepository.updateDownloadState(videoId, DownloadState.STATE_DOWNLOADED)
                                    listJob.value.find { it.videoId == videoId }?.let {
                                        mainRepository.getSongById(videoId).collect { song ->
                                            if (song != null) {
                                                val temp: ArrayList<SongEntity> = arrayListOf()
                                                temp.addAll(listJob.value)
                                                temp[listJob.value.indexOf(listJob.value.find { it.videoId == song.videoId })] = song
                                                listJob.value = temp
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        Download.STATE_FAILED -> {
                            mainRepository.getSongById(videoId).collect{ song ->
                                if (song?.downloadState != DownloadState.STATE_NOT_DOWNLOADED) {
                                    mainRepository.updateDownloadState(videoId, DownloadState.STATE_NOT_DOWNLOADED)
                                    listJob.value.find { it.videoId == videoId }?.let {
                                        mainRepository.getSongById(videoId).collect { song ->
                                            if (song != null) {
                                                val temp: ArrayList<SongEntity> = arrayListOf()
                                                temp.addAll(listJob.value)
                                                temp[listJob.value.indexOf(listJob.value.find { it.videoId == song.videoId })] = song
                                                listJob.value = temp
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        Download.STATE_DOWNLOADING -> {
                            mainRepository.getSongById(videoId).collect{ song ->
                                if (song != null) {
                                    if (song.downloadState != DownloadState.STATE_DOWNLOADING) {
                                        mainRepository.updateDownloadState(videoId, DownloadState.STATE_DOWNLOADING)
                                        listJob.value.find { it.videoId == videoId }?.let {
                                            mainRepository.getSongById(videoId).collect { song ->
                                                if (song != null) {
                                                    val temp: ArrayList<SongEntity> = arrayListOf()
                                                    temp.addAll(listJob.value)
                                                    temp[listJob.value.indexOf(listJob.value.find { it.videoId == song.videoId })] = song
                                                    listJob.value = temp
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        Download.STATE_QUEUED -> {
                            mainRepository.getSongById(videoId).collect{ song ->
                                if (song?.downloadState != DownloadState.STATE_NOT_DOWNLOADED) {
                                    mainRepository.updateDownloadState(videoId, DownloadState.STATE_NOT_DOWNLOADED)
                                    listJob.value.find { it.videoId == videoId }?.let {
                                        mainRepository.getSongById(videoId).collect { song ->
                                            if (song != null) {
                                                val temp: ArrayList<SongEntity> = arrayListOf()
                                                temp.addAll(listJob.value)
                                                temp[listJob.value.indexOf(listJob.value.find { it.videoId == song.videoId })] = song
                                                listJob.value = temp
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        else -> {
                            Log.d("Check Downloaded", "Not Downloaded")
                        }
                    }
                }
            }
        }
    }

    fun clearPlaylistBrowse() {
        _playlistBrowse.value = null
    }

    fun getLocation() {
        regionCode = runBlocking { dataStoreManager.location.first() }
        language = runBlocking { dataStoreManager.getString(SELECTED_LANGUAGE).first() }
    }
    fun getSongEntity(song: SongEntity) {
        viewModelScope.launch {
            mainRepository.insertSong(song)
            mainRepository.getSongById(song.videoId).collect { values ->
                _songEntity.value = values
            }
        }
    }

    fun updateLikeStatus(videoId: String, likeStatus: Int) {
        viewModelScope.launch {
            mainRepository.updateLikeStatus(likeStatus = likeStatus, videoId = videoId)
        }
    }

    fun getLocalPlaylist() {
        viewModelScope.launch {
            mainRepository.getAllLocalPlaylists().collect { values ->
                _listLocalPlaylist.postValue(values)
            }
        }
    }

    fun updateLocalPlaylistTracks(list: List<String>, id: Long) {
        viewModelScope.launch {
            mainRepository.getSongsByListVideoId(list).collect { values ->
                var count = 0
                values.forEach { song ->
                    if (song.downloadState == DownloadState.STATE_DOWNLOADED){
                        count++
                    }
                }
                mainRepository.updateLocalPlaylistTracks(list, id)
                Toast.makeText(getApplication(), "Added to playlist", Toast.LENGTH_SHORT).show()
                if (count == values.size) {
                    mainRepository.updateLocalPlaylistDownloadState(DownloadState.STATE_DOWNLOADED, id)
                }
                else {
                    mainRepository.updateLocalPlaylistDownloadState(DownloadState.STATE_NOT_DOWNLOADED, id)
                }
            }
        }
    }
    fun updateDownloadState(videoId: String, state: Int) {
        viewModelScope.launch {
            mainRepository.updateDownloadState(videoId, state)
            listJob.value.find { it.videoId == videoId }?.let {
                mainRepository.getSongById(videoId).collect { song ->
                    if (song != null) {
                        val temp: ArrayList<SongEntity> = arrayListOf()
                        temp.addAll(listJob.value)
                        temp[listJob.value.indexOf(listJob.value.find { it.videoId == song.videoId })] = song
                        listJob.value = temp
                    }
                }
            }
        }
    }

    fun insertSong(songEntity: SongEntity) {
        viewModelScope.launch {
            mainRepository.insertSong(songEntity)
        }
    }
}