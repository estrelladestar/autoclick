package com.estrella.sorteos

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import org.json.JSONObject

/**
 * Pagos de Google Play (solo en la versión de la tienda).
 * Suscripciones creadas en Play Console → Monetizar → Suscripciones:
 *   starter, creator, agency   (cada una con los planes base «mensual» y «anual»)
 * El plan de la app es la suscripción activa más alta.
 */
object Pagos {
    const val enabled = true
    private val IDS = linkedMapOf("starter" to 'S', "creator" to 'C', "agency" to 'A')
    private const val PREFS = "pagos"

    private var client: BillingClient? = null
    private var ctx: Context? = null
    private var onChange: (() -> Unit)? = null
    private val details = HashMap<String, ProductDetails>()
    private val main = Handler(Looper.getMainLooper())

    fun start(act: Activity, onChange: () -> Unit) {
        ctx = act.applicationContext
        this.onChange = onChange
        val c = BillingClient.newBuilder(act.applicationContext)
            .setListener { r, purchases -> if (r.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) handle(purchases, true) }
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .enableAutoServiceReconnection()
            .build()
        client = c
        c.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(r: BillingResult) {
                if (r.responseCode != BillingClient.BillingResponseCode.OK) return
                loadProducts(); refresh()
            }
            override fun onBillingServiceDisconnected() {}
        })
    }

    fun end() { try { client?.endConnection() } catch (e: Exception) {}; client = null }

    private fun loadProducts() {
        val list = IDS.keys.map {
            QueryProductDetailsParams.Product.newBuilder().setProductId(it).setProductType(BillingClient.ProductType.SUBS).build()
        }
        client?.queryProductDetailsAsync(QueryProductDetailsParams.newBuilder().setProductList(list).build()) { r, res ->
            if (r.responseCode == BillingClient.BillingResponseCode.OK) {
                for (d in res.productDetailsList) details[d.productId] = d
                main.post { onChange?.invoke() }
            }
        }
    }

    /** Vuelve a mirar qué suscripciones tiene esta cuenta de Google. */
    fun refresh() {
        client?.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()) { r, list ->
            if (r.responseCode == BillingClient.BillingResponseCode.OK) handle(list, false, replace = true)
        }
    }

    private fun handle(purchases: List<Purchase>, fromBuy: Boolean, replace: Boolean = false) {
        var best: Char? = null
        for (p in purchases) {
            if (p.purchaseState != Purchase.PurchaseState.PURCHASED) continue
            // Hay que «aceptar» cada compra; si no, Google la devuelve a los 3 días
            if (!p.isAcknowledged) {
                client?.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(p.purchaseToken).build()) {}
            }
            for (id in p.products) {
                val c = IDS[id] ?: continue
                if (best == null || "SCA".indexOf(c) > "SCA".indexOf(best!!)) best = c
            }
        }
        val prefs = ctx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE) ?: return
        val old = prefs.getString("plan", "F")
        val now = best?.toString() ?: if (replace) "F" else old ?: "F"
        prefs.edit().putString("plan", now).apply()
        if (now != old || fromBuy) main.post { onChange?.invoke() }
    }

    /** Plan actual para la app: {"plan":"C","store":true}. */
    fun planJson(): String? {
        val plan = ctx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.getString("plan", "F") ?: "F"
        return JSONObject().put("plan", plan).put("store", true).put("canActivate", false).toString()
    }

    /** Precios de la tienda: {"S":{"m":"9,99 €","y":"99,90 €"},...} */
    fun productsJson(): String {
        val o = JSONObject()
        for ((id, c) in IDS) {
            val d = details[id] ?: continue
            val p = JSONObject()
            d.subscriptionOfferDetails?.forEach { off ->
                if (off.offerId != null) return@forEach            // solo el plan base (sin ofertas)
                val price = off.pricingPhases.pricingPhaseList.lastOrNull()?.formattedPrice ?: return@forEach
                when (off.basePlanId) { "mensual" -> p.put("m", price); "anual" -> p.put("y", price) }
            }
            o.put(c.toString(), p)
        }
        return o.toString()
    }

    fun buy(act: Activity, plan: String, period: String) {
        val id = IDS.entries.firstOrNull { it.value.toString() == plan }?.key ?: return
        val d = details[id] ?: run { loadProducts(); return }
        val base = if (period == "y") "anual" else "mensual"
        val off = d.subscriptionOfferDetails?.firstOrNull { it.basePlanId == base && it.offerId == null }
            ?: d.subscriptionOfferDetails?.firstOrNull { it.basePlanId == base } ?: return
        val params = BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(
            BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(d).setOfferToken(off.offerToken).build()
        )).build()
        client?.launchBillingFlow(act, params)
    }

    /** Abre la página de Google Play para cancelar o cambiar la suscripción. */
    fun manage(act: Activity) {
        val u = Uri.parse("https://play.google.com/store/account/subscriptions?package=" + act.packageName)
        try { act.startActivity(Intent(Intent.ACTION_VIEW, u)) } catch (e: Exception) {}
    }
}
