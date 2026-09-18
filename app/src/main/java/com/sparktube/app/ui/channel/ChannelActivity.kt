package com.sparktube.app.ui.channel

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.sparktube.app.R
import com.sparktube.app.data.ChannelEntry
import com.sparktube.app.data.LocalStore
import com.sparktube.app.data.YtRepository
import com.sparktube.app.databinding.ActivityChannelBinding
import com.sparktube.app.ui.common.SkeletonAdapter
import com.sparktube.app.ui.common.showSkeleton
import com.sparktube.app.ui.common.toUiModel
import com.sparktube.app.ui.common.VideoAdapter
import com.sparktube.app.ui.player.PlayerActivity
import com.sparktube.app.util.Formatters
import com.sparktube.app.util.Thumbs
import com.sparktube.app.util.Themes
import kotlinx.coroutines.launch
import org.schabi.newpipe.extractor.Page

/** Channel browser: header with subscribe button + paginated video list. */
class ChannelActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChannelBinding
    private lateinit var adapter: VideoAdapter

    private val items = mutableListOf<com.sparktube.app.ui.common.VideoUiModel>()
    private var page: Page? = null
    private var isLoading = false
    private var channelUrl: String = ""
    private var channelAvatar: String = ""
    private var subscriberCount: Long = -1L
    private var channelName: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        Themes.apply(this)
        super.onCreate(savedInstanceState)
        binding = ActivityChannelBinding.inflate(layoutInflater)
        setContentView(binding.root)

        channelUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
        channelName = intent.getStringExtra(EXTRA_NAME).orEmpty()
        if (channelUrl.isBlank()) {
            finish()
            return
        }

        binding.channelName.text = channelName

        adapter = VideoAdapter(onClick = { model ->
            PlayerActivity.start(this, model)
        })
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter

        binding.list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0) return
                val lm = binding.list.layoutManager as? LinearLayoutManager ?: return
                val lastVisible = lm.findLastVisibleItemPosition()
                if (lastVisible >= adapter.itemCount - 4 && page != null && !isLoading) {
                    loadMore()
                }
            }
        })

        binding.backButton.setOnClickListener { finish() }
        binding.subscribeButton.setOnClickListener { toggleSubscribe() }

        load()
    }

    override fun onResume() {
        super.onResume()
        // A theme / accent change while we were in the background.
        Themes.recreateIfNeeded(this)
        updateSubscribeUi()
    }

    private fun load() {
        binding.errorView.isVisible = false
        // Skeleton cards instead of a centered spinner.
        showSkeleton(binding.list, SkeletonAdapter.STYLE_VIDEO, count = 8)
        lifecycleScope.launch {
            try {
                val channel = YtRepository.channelInfo(channelUrl)
                channelName = channel.name
                channelAvatar = channel.avatarUrl
                subscriberCount = channel.subscriberCount
                binding.channelName.text = channel.name
                binding.channelSubs.text =
                    getString(R.string.subscribers_fmt, Formatters.formatViewCount(channel.subscriberCount))
                Thumbs.load(binding.channelAvatar, channel.avatarUrl)
                updateSubscribeUi()

                val (videos, nextPage) = YtRepository.channelVideos(channelUrl)
                page = nextPage
                val known = items.map { it.url }.toSet()
                items.addAll(videos.map { it.toUiModel() }.filter { it.url !in known })
                binding.list.adapter = adapter
                adapter.submitList(items.toList())
                binding.emptyView.isVisible = items.isEmpty()
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Leaving the screen / reload: not an error.
                throw e
            } catch (e: Exception) {
                binding.list.adapter = adapter
                adapter.submitList(emptyList())
                binding.errorText.text = Formatters.friendlyException(e)
                binding.errorView.isVisible = true
            }
        }
    }

    private fun loadMore() {
        val nextPage = page ?: return
        isLoading = true
        lifecycleScope.launch {
            try {
                val (videos, next) = YtRepository.channelVideosMore(channelUrl, nextPage)
                page = next
                val known = items.map { it.url }.toSet()
                items.addAll(videos.map { it.toUiModel() }.filter { it.url !in known })
                adapter.submitList(items.toList())
            } catch (e: Exception) {
                page = null
            } finally {
                isLoading = false
            }
        }
    }

    private fun toggleSubscribe() {
        val nowSubscribed = LocalStore.toggleSubscription(
            this,
            ChannelEntry(
                url = channelUrl,
                name = channelName,
                avatarUrl = channelAvatar,
                subscriberCount = subscriberCount
            )
        )
        Toast.makeText(
            this,
            if (nowSubscribed) R.string.subscribed_toast else R.string.unsubscribed_toast,
            Toast.LENGTH_SHORT
        ).show()
        updateSubscribeUi()
    }

    private fun updateSubscribeUi() {
        val subscribed = LocalStore.isSubscribed(this, channelUrl)
        binding.subscribeButton.text = getString(if (subscribed) R.string.subscribed else R.string.subscribe)
        binding.subscribeButton.setBackgroundResource(
            if (subscribed) R.drawable.bg_subscribe_on else R.drawable.bg_subscribe_off
        )
    }

    companion object {
        private const val EXTRA_URL = "extra_channel_url"
        private const val EXTRA_NAME = "extra_channel_name"

        fun start(context: Context, url: String, name: String) {
            val intent = Intent(context, ChannelActivity::class.java).apply {
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_NAME, name)
            }
            context.startActivity(intent)
        }
    }
}
