package com.mstr.btccompare.data

/**
 * MSTR fundamentals used for mNAV calculation.
 *
 * NOTE: these change over time (Saylor keeps buying BTC and issuing shares).
 * Update quarterly from MSTR's investor relations page or the latest 10-Q.
 *
 * As of late 2025 the rough numbers were:
 *   - BTC holdings:    ~640,000 BTC
 *   - Shares outstanding (basic): ~280,000,000
 *
 * Default account-size for the position-sizing helper. The user can mentally
 * scale by the ratio of their account to this number.
 */
object MstrFundamentals {
    const val btcHoldings: Long = 640_000L
    const val sharesOutstanding: Long = 280_000_000L

    const val defaultAccountSize: Double = 10_000.0
    const val maxRiskPerTrade: Double = 0.02     // 2 %
    const val dailyLossLimit: Double = 0.05      // 5 %
}
