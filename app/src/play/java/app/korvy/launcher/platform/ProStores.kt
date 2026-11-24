package app.korvy.launcher.platform

import android.app.Activity
import android.content.Context
import android.os.Build
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

fun createProStore(context: Context): ProStore = PlayPro(context.applicationContext)

/**
 * Play Billing for the one-time "pro_unlock" product. The result is remembered on the phone so Pro works
 * offline and nothing slows startup; Play is asked again by [refresh] (Settings, or when a Pro feature opens).
 * Copies not installed by the Play Store (test builds, builds from source) are unlocked: the code is open source
 * and the F-Droid build is free anyway.
 */
private class PlayPro(private val context: Context) : ProStore, PurchasesUpdatedListener {
    private val prefs = context.getSharedPreferences("pro", Context.MODE_PRIVATE)
    private val fromPlay = installer() == "com.android.vending"
    private val _unlocked = MutableStateFlow(!fromPlay || prefs.getBoolean(KEY, false))
    override val unlocked: StateFlow<Boolean> = _unlocked
    private val _price = MutableStateFlow<String?>(null)
    override val price: StateFlow<String?> = _price
    override val canBuy get() = fromPlay
    private var details: ProductDetails? = null
    private var pendingBuy: Activity? = null

    private val client by lazy {
        BillingClient.newBuilder(context).setListener(this)
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .enableAutoServiceReconnection()
            .build()
    }

    private fun installer(): String? = runCatching {
        if (Build.VERSION.SDK_INT >= 30) context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
        else @Suppress("DEPRECATION") context.packageManager.getInstallerPackageName(context.packageName)
    }.getOrNull()

    private fun connected(then: () -> Unit) {
        if (client.isReady) { then(); return }
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(r: BillingResult) { if (r.responseCode == BillingClient.BillingResponseCode.OK) then() }
            override fun onBillingServiceDisconnected() {}
        })
    }

    override fun refresh() {
        if (!fromPlay) return
        connected {
            client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()) { r, list ->
                if (r.responseCode == BillingClient.BillingResponseCode.OK) handle(list, complete = true)
            }
            client.queryProductDetailsAsync(QueryProductDetailsParams.newBuilder().setProductList(listOf(
                QueryProductDetailsParams.Product.newBuilder().setProductId(PRODUCT).setProductType(BillingClient.ProductType.INAPP).build(),
            )).build()) { _, result ->
                details = result.productDetailsList.firstOrNull()
                _price.value = details?.oneTimePurchaseOfferDetails?.formattedPrice
                pendingBuy?.let { a -> pendingBuy = null; launch(a) }
            }
        }
    }

    override fun buy(activity: Activity) {
        if (!fromPlay) return
        if (details == null) { pendingBuy = activity; refresh() } else launch(activity)
    }

    private fun launch(activity: Activity) {
        val d = details ?: return
        client.launchBillingFlow(activity, BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(
            BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(d).build(),
        )).build())
    }

    override fun onPurchasesUpdated(r: BillingResult, purchases: MutableList<Purchase>?) {
        if (r.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) handle(purchases, complete = false)
        else if (r.responseCode == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED) refresh()
    }

    /** [complete]: the full list of what's owned (so no Pro purchase means it was refunded). */
    private fun handle(purchases: List<Purchase>, complete: Boolean) {
        val p = purchases.firstOrNull { PRODUCT in it.products && it.purchaseState == Purchase.PurchaseState.PURCHASED }
        if (p != null) {
            set(true)
            // Unacknowledged purchases are refunded by Play after 3 days.
            if (!p.isAcknowledged) connected {
                client.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(p.purchaseToken).build()) {}
            }
        } else if (complete) set(false)
    }

    private fun set(v: Boolean) { _unlocked.value = v; prefs.edit().putBoolean(KEY, v).apply() }

    companion object {
        const val PRODUCT = "pro_unlock"
        private const val KEY = "unlocked"
    }
}
