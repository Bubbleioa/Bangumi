package soko.ekibun.bangumi.ui.main.fragment.home.fragment.collection

import android.annotation.SuppressLint
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.viewpager.widget.ViewPager
import kotlinx.android.synthetic.main.fragment_collection.*
import retrofit2.Call
import soko.ekibun.bangumi.R
import soko.ekibun.bangumi.api.ApiHelper
import soko.ekibun.bangumi.api.bangumi.Bangumi
import soko.ekibun.bangumi.api.bangumi.bean.Collection
import soko.ekibun.bangumi.api.bangumi.bean.Episode
import soko.ekibun.bangumi.api.bangumi.bean.Subject
import soko.ekibun.bangumi.ui.main.MainActivity
import soko.ekibun.bangumi.ui.subject.SubjectActivity
import soko.ekibun.bangumi.ui.view.BrvahLoadMoreView

/**
 * 收藏PagerAdapter
 */
class CollectionPagerAdapter(private val context: Context, val fragment: CollectionFragment, private val pager: ViewPager, private val scrollTrigger: (Boolean) -> Unit) : androidx.viewpager.widget.PagerAdapter() {
    private var tabList = context.resources.getStringArray(R.array.collection_status_anime)
    private val subjectTypeView = SubjectTypeView(fragment.item_type) { reset() }

    init {
        pager.addOnPageChangeListener(object : ViewPager.OnPageChangeListener {
            override fun onPageScrollStateChanged(state: Int) { /* no-op */
            }

            override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) { /* no-op */
            }

            override fun onPageSelected(position: Int) {
                if ((items[position]?.second?.tag as? RecyclerView)?.tag == null) {
                    pageIndex[position] = 0
                    loadCollectionList(position)
                }
                scrollTrigger((items[position]?.second?.tag as? RecyclerView)?.canScrollVertically(-1) == true)
            }
        })
    }

    @SuppressLint("UseSparseArrays")
    private val items = HashMap<Int, Pair<CollectionListAdapter, SwipeRefreshLayout>>()

    override fun instantiateItem(container: ViewGroup, position: Int): Any {
        val item = items.getOrPut(position) {
            val swipeRefreshLayout = SwipeRefreshLayout(container.context)
            val recyclerView = RecyclerView(container.context)
            recyclerView.overScrollMode = View.OVER_SCROLL_NEVER
            recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    scrollTrigger((items[pager.currentItem]?.second?.tag as? RecyclerView)?.canScrollVertically(-1) == true)
                }
            })

            val adapter = CollectionListAdapter()
            adapter.emptyView = LayoutInflater.from(container.context).inflate(R.layout.view_empty, container, false)
            adapter.isUseEmpty(false)
            adapter.setEnableLoadMore(true)
            adapter.setLoadMoreView(BrvahLoadMoreView())
            adapter.setOnLoadMoreListener({
                val useApi = position == 2 && subjectTypeView.selectedType in arrayOf(R.id.collection_type_anime, R.id.collection_type_book, R.id.collection_type_real)
                if (!swipeRefreshLayout.isRefreshing && !useApi) loadCollectionList(position)
            }, recyclerView)
            adapter.setOnItemClickListener { _, v, position ->
                SubjectActivity.startActivity(v.context, adapter.data[position])
            }
            recyclerView.adapter = adapter
            recyclerView.layoutManager = LinearLayoutManager(container.context)
            recyclerView.isNestedScrollingEnabled = false
            swipeRefreshLayout.addView(recyclerView)
            swipeRefreshLayout.tag = recyclerView
            swipeRefreshLayout.setOnRefreshListener { reset() }
            Pair(adapter, swipeRefreshLayout)
        }
        container.addView(item.second)
        if (pageIndex[position] == 0)
            loadCollectionList(position)
        return item.second
    }

    /**
     * 重置
     */
    fun reset() {
        tabList = context.resources.getStringArray(Collection.getStatusNamesRes(subjectTypeView.getType()))
        notifyDataSetChanged()

        items.forEach { (it.value.second.tag as? RecyclerView)?.tag = null }
        pageIndex.clear()
        loadCollectionList()
    }

    @SuppressLint("UseSparseArrays")
    private var collectionCalls = HashMap<Int, Call<List<Subject>>>()
    @SuppressLint("UseSparseArrays")
    private val pageIndex = HashMap<Int, Int>()

    private fun loadCollectionList(position: Int = pager.currentItem) {
        val item = items[position] ?: return
        item.second.isRefreshing = false
        item.first.isUseEmpty(false)
        val page = pageIndex.getOrPut(position) { 0 }
        collectionCalls[position]?.cancel()
        val useApi = position == 2 && subjectTypeView.selectedType in arrayOf(R.id.collection_type_anime, R.id.collection_type_book, R.id.collection_type_real)
        if (page == 0) {
            if (!useApi) item.first.setNewData(null)
            item.second.isRefreshing = true
        }
        val callback = { list: List<Subject> ->
            item.first.isUseEmpty(true)
            list.filter { !useApi || it.type == subjectTypeView.getType() }.let {
                if (!useApi) {
                    it.forEach { it.type = subjectTypeView.getType() }
                    item.first.addData(it)
                } else {
                    item.first.setNewData(it.sortedByDescending {
                        val eps = it.eps?.filter { it.type == Episode.TYPE_MAIN }
                        val watchTo = eps?.lastOrNull { it.progress == Episode.PROGRESS_WATCH }
                        val airTo = eps?.lastOrNull { it.status == Episode.STATUS_AIR }
                        (if (watchTo != airTo) ":" else "") + (airTo?.airdate ?: "")
                    }.toMutableList())
                }
            }
            if (useApi || list.size < 10)
                item.first.loadMoreEnd()
            else
                item.first.loadMoreComplete()
            (item.second.tag as? RecyclerView)?.tag = true
            pageIndex[position] = (pageIndex[position] ?: 0) + 1
        }
        val onError = { it: Throwable? ->
            item.second.isRefreshing = false
            item.first.loadMoreFail()
        }
        if (useApi) (fragment.activity as? MainActivity)?.mainPresenter?.updateUserCollection(callback, onError)
        else {
            collectionCalls[position] = Bangumi.getCollectionList(subjectTypeView.getType(),
                    (fragment.activity as? MainActivity)?.user?.let { it.username ?: it.id.toString() } ?: return,
                    Collection.getStatusById(position + 1), page + 1)
            collectionCalls[position]?.enqueue(ApiHelper.buildCallback(callback, onError))
        }
    }

    override fun getPageTitle(pos: Int): CharSequence {
        return tabList[pos]
    }

    override fun isViewFromObject(view: View, `object`: Any): Boolean {
        return view === `object`
    }

    override fun getCount(): Int {
        return tabList.size
    }

    override fun destroyItem(container: ViewGroup, position: Int, `object`: Any) {
        container.removeView(`object` as View)
    }

}