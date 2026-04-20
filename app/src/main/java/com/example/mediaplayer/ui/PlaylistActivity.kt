package com.example.mediaplayer.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.*
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.mediaplayer.R
import com.example.mediaplayer.db.AppDatabase
import com.example.mediaplayer.db.entity.Playlist
import com.example.mediaplayer.db.entity.PlaylistItem
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.launch

class PlaylistActivity : AppCompatActivity() {

    companion object {
        const val RESULT_PLAY  = Activity.RESULT_FIRST_USER + 1
        const val EXTRA_URIS   = "track_uris"
        const val EXTRA_NAMES  = "track_names"
        const val EXTRA_START  = "start_index"
        private const val REQ_AUDIO = 201
    }

    private val db by lazy { AppDatabase.get(this) }
    private val playlists = mutableListOf<Playlist>()
    private var selectedPlaylist: Playlist? = null
    private val tracks = mutableListOf<PlaylistItem>()

    private lateinit var rvPlaylists: RecyclerView
    private lateinit var rvTracks: RecyclerView
    private lateinit var tvHeader: TextView
    private lateinit var btnBack: ImageButton
    private lateinit var fab: FloatingActionButton

    private var inTrackView = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_playlist)

        rvPlaylists = findViewById(R.id.rvPlaylists)
        rvTracks    = findViewById(R.id.rvTracks)
        tvHeader    = findViewById(R.id.tvPlaylistHeader)
        btnBack     = findViewById(R.id.btnPlaylistBack)
        fab         = findViewById(R.id.fabPlaylist)

        rvPlaylists.layoutManager = LinearLayoutManager(this)
        rvTracks.layoutManager    = LinearLayoutManager(this)

        btnBack.setOnClickListener {
            if (inTrackView) showPlaylistView()
            else finish()
        }

        fab.setOnClickListener {
            if (inTrackView) pickAudioFiles()
            else showCreatePlaylistDialog()
        }

        showPlaylistView()
        observePlaylists()
    }

    private fun showPlaylistView() {
        inTrackView = false
        rvPlaylists.visibility = View.VISIBLE
        rvTracks.visibility    = View.GONE
        tvHeader.text = "My Playlists"
        fab.setImageResource(R.drawable.ic_add)
    }

    private fun observePlaylists() {
        db.playlistDao().getAll().observe(this) { list ->
            playlists.clear(); playlists.addAll(list)
            rvPlaylists.adapter = PlaylistListAdapter(db, this, playlists,
                onTap    = { openPlaylist(it) },
                onDelete = { confirmDeletePlaylist(it) }
            )
        }
    }

    private fun showCreatePlaylistDialog() {
        val et = EditText(this).apply {
            hint = "Playlist name"
            setPadding(60, 32, 60, 16)
            setTextColor(0xFFF2F2FF.toInt())
            setHintTextColor(0xFF6B6B88.toInt())
        }
        AlertDialog.Builder(this, R.style.DialogTheme)
            .setTitle("New Playlist").setView(et)
            .setPositiveButton("Create") { _, _ ->
                val name = et.text.toString().trim()
                if (name.isNotEmpty()) lifecycleScope.launch {
                    db.playlistDao().insert(Playlist(name = name))
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun confirmDeletePlaylist(p: Playlist) {
        AlertDialog.Builder(this, R.style.DialogTheme)
            .setTitle("Delete \"${p.name}\"?")
            .setMessage("This will also remove all tracks in this playlist.")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    db.playlistDao().deleteByPlaylistId(p.id)  // delete tracks first
                    db.playlistDao().delete(p)                 // then delete playlist
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun openPlaylist(p: Playlist) {
        selectedPlaylist = p
        inTrackView = true
        rvPlaylists.visibility = View.GONE
        rvTracks.visibility    = View.VISIBLE
        tvHeader.text = p.name
        fab.setImageResource(R.drawable.ic_folder)

        db.playlistDao().getItems(p.id).observe(this) { list ->
            tracks.clear(); tracks.addAll(list)
            rvTracks.adapter = TrackListAdapter(tracks,
                onPlay   = { _, idx -> sendQueue(idx) },
                onDelete = { item ->
                    lifecycleScope.launch { db.playlistDao().deleteItem(item); }
                }
            )
        }
    }

    private fun pickAudioFiles() {
        startActivityForResult(
            Intent.createChooser(
                Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    type = "audio/*"; addCategory(Intent.CATEGORY_OPENABLE)
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, "Add Audio Files"
            ), REQ_AUDIO
        )
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_AUDIO || resultCode != RESULT_OK) return
        lifecycleScope.launch {
            val uris = mutableListOf<Uri>()
            data?.clipData?.let { for (i in 0 until it.itemCount) uris.add(it.getItemAt(i).uri) }
                ?: data?.data?.let { uris.add(it) }
            val pid = selectedPlaylist?.id ?: return@launch
            uris.forEach { uri ->
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) {}
                db.playlistDao().insertItem(
                    PlaylistItem(playlistId = pid, uriString = uri.toString(),
                                 name = getFileName(uri), position = tracks.size)
                )
            }
            Toast.makeText(this@PlaylistActivity, "${uris.size} track(s) added", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendQueue(startIdx: Int) {
        if (tracks.isEmpty()) { Toast.makeText(this, "No tracks", Toast.LENGTH_SHORT).show(); return }
        setResult(RESULT_PLAY, Intent().apply {
            putStringArrayListExtra(EXTRA_URIS,  ArrayList(tracks.map { it.uriString }))
            putStringArrayListExtra(EXTRA_NAMES, ArrayList(tracks.map { it.name }))
            putExtra(EXTRA_START, startIdx)
        })
        finish()
    }

    private fun getFileName(uri: Uri): String = try {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            c.moveToFirst(); if (idx >= 0) c.getString(idx) else uri.lastPathSegment ?: "Track"
        } ?: (uri.lastPathSegment ?: "Track")
    } catch (_: Exception) { uri.lastPathSegment ?: "Track" }
}

class PlaylistListAdapter(
    private val db: AppDatabase,
    private val owner: androidx.lifecycle.LifecycleOwner,
    private val items: List<Playlist>,
    private val onTap: (Playlist) -> Unit,
    private val onDelete: (Playlist) -> Unit
) : RecyclerView.Adapter<PlaylistListAdapter.VH>() {

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val icon:     ImageView  = v.findViewById(R.id.ivPlaylistIcon)
        val name:     TextView   = v.findViewById(R.id.tvPlaylistItemName)
        val meta:     TextView   = v.findViewById(R.id.tvPlaylistItemMeta)
        val btnMore:  ImageButton = v.findViewById(R.id.btnPlaylistMore)
    }

    override fun onCreateViewHolder(p: ViewGroup, t: Int) =
        VH(LayoutInflater.from(p.context).inflate(R.layout.item_playlist, p, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, i: Int) {
        val pl = items[i]
        h.name.text = pl.name
        db.playlistDao().getItemCount(pl.id).observe(owner) { count ->
            h.meta.text = count.toString()
        }
        h.itemView.setOnClickListener { onTap(pl) }
        h.btnMore.setOnClickListener {
            val popup = PopupMenu(h.btnMore.context, h.btnMore)
            popup.menu.add("Delete Playlist")
            popup.setOnMenuItemClickListener {
                onDelete(pl)
                true
            }
            popup.show()
        }
    }
}

class TrackListAdapter(
    private val items: List<PlaylistItem>,
    private val onPlay:   (PlaylistItem, Int) -> Unit,
    private val onDelete: (PlaylistItem) -> Unit
) : RecyclerView.Adapter<TrackListAdapter.VH>() {

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val index: TextView = v.findViewById(R.id.tvTrackIndex)
        val name:  TextView = v.findViewById(R.id.tvTrackName)
        val artist: TextView = v.findViewById(R.id.tvTrackArtist)
        val more:  ImageButton = v.findViewById(R.id.btnDeleteTrack)
    }

    override fun onCreateViewHolder(p: ViewGroup, t: Int) =
        VH(LayoutInflater.from(p.context).inflate(R.layout.item_track, p, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, i: Int) {
        val track = items[i]
        h.index.text = (i + 1).toString()
        h.name.text = track.name
        h.artist.text = "Local Audio"
        h.itemView.setOnClickListener { onPlay(track, i) }
        h.more.setOnClickListener {
            val popup = PopupMenu(h.more.context, h.more)
            popup.menu.add("Remove from Playlist")
            popup.setOnMenuItemClickListener {
                onDelete(track)
                true
            }
            popup.show()
        }
    }
}
