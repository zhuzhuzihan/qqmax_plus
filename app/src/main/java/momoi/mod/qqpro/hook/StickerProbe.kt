package momoi.mod.qqpro.hook

import com.tencent.qqnt.kernel.nativeinterface.CustomEmotionData
import com.tencent.qqnt.kernel.nativeinterface.IFetchMarketEmoticonListCallback
import com.tencent.qqnt.kernel.nativeinterface.IGProFetchFavEmojiListCallback
import com.tencent.qqnt.kernel.nativeinterface.MarketEmoticonInfo
import com.tencent.qqnt.msg.KernelServiceUtil
import momoi.mod.qqpro.util.Utils
import java.util.ArrayList

/**
 * One-shot FEASIBILITY PROBE (no UI) for the store-sticker (商城表情) feature. Answers the open
 * question: do the watch's market-emoticon kernel APIs actually return data (owned fav stickers +
 * roaming owned packs added on the phone), or are they server-gated for the watch device type like
 * group-file was? Fires once per process on chat resume; results land in qqpro_debug.log.
 *
 * Callbacks are public top-level classes (not @Mixin-inline anon classes, which crash with
 * IllegalAccessError — see qqpro-mixin-anon-class memory).
 */
private var probed = false

fun runStickerProbe() {
    if (probed) return
    probed = true
    Thread {
        // 1) Owned / favourited stickers (incl. saved market faces). IMsgService(api).fetchFavEmojiList
        //    — same call the native fav tab (FavEmotionRepository) makes: (resId, count, backwardFetch, forceRefresh, cb)
        runCatching {
            val msg = KernelServiceUtil.c()
            Utils.log("StickerProbe: IMsgService=${msg != null}")
            msg?.fetchFavEmojiList("", 30, false, true, FavEmojiProbe())
        }.onFailure { Utils.log("StickerProbe fav err: $it") }

        // 2) Roaming owned PACKS (商城表情 added on the phone). IKernelMsgService.fetchMarketEmoticonList
        //    reached via the wrapper session (the path QQ's own addFavEmoji uses).
        runCatching {
            val ks = KernelServiceUtil.g()?.msgService
            Utils.log("StickerProbe: IKernelMsgService=${ks != null}")
            ks?.fetchMarketEmoticonList(0, 0, MarketListProbe())
        }.onFailure { Utils.log("StickerProbe market err: $it") }
    }.start()
}

class FavEmojiProbe : IGProFetchFavEmojiListCallback {
    override fun onFetchFavEmojiListCallback(code: Int, msg: String?, list: ArrayList<CustomEmotionData>?) {
        val n = list?.size ?: 0
        val mk = list?.count { it.isMarkFace } ?: 0
        Utils.log("StickerProbe FAV: code=$code msg=$msg total=$n markFace=$mk")
        list?.take(10)?.forEach {
            Utils.log("  fav eId=${it.eId} epId=${it.epId} mark=${it.isMarkFace} apng=${it.isAPNG} exist=${it.isExist} path=${it.emoPath} url=${it.url}")
        }
    }
}

class MarketListProbe : IFetchMarketEmoticonListCallback {
    override fun onFetchMarketEmoticonListCallback(code: Int, msg: String?, info: MarketEmoticonInfo?) {
        val tab = info?.roamEmojiTab
        Utils.log(
            "StickerProbe MARKET: code=$code msg=$msg businessId=${info?.businessId} result=${info?.result} " +
                "epIds=${tab?.epIds} ordinary=${tab?.ordinaryTabinfoList?.size} magic=${tab?.magicTabinfoList?.size} small=${tab?.smallTabinfoList?.size}"
        )
        tab?.ordinaryTabinfoList?.take(30)?.forEach {
            Utils.log("  pack epId=${it.epId} name=${it.tabName} type=${it.tabType} flags=${it.flags} expire=${it.expireTime}")
        }
        tab?.smallTabinfoList?.take(30)?.forEach {
            Utils.log("  small epId=${it.epId} name=${it.tabName} type=${it.tabType}")
        }
    }
}
