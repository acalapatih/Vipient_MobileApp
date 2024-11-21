package com.acalapatih.vipient

class AppSettings {
    companion object {
        //this flag will be handled by subscription
        var isUserPaid = false

        // enable admob or facebook ads, by default admob ads are enable
        // set flags true or false
        val enableAdmobAds = false
        val enableFacebookAds = false

        //place your one signal id
        val oneSignalId = "85a43129-fbb6-4969-8d09-f4e9d168a64e"

        //Subscription id's
        val one_month_subscription_id = "put your one month subscription id here"
        val three_month_subscription_id = "put your three months subscription id here"
        val one_year_subscription_id = "put your one year subscription id here"
    }
}