package com.kendovivi.mediapicker

import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import android.support.v4.app.FragmentManager
import android.support.v4.view.ViewPager

import android.support.design.widget.TabLayout

class MainActivity : AppCompatActivity() {

    var mediaPickerTabMenuPagerAdapter: MediaPickerTabMenuPagerAdapter? = null
    var viewPager: ViewPager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val tabMenuList: List<String> = listOf("ギャラリー", "写真", "動画")

        viewPager = findViewById(R.id.view_pager)
        val fm: FragmentManager = getSupportFragmentManager()

        mediaPickerTabMenuPagerAdapter = MediaPickerTabMenuPagerAdapter(tabMenuList,fm)
        viewPager?.adapter = mediaPickerTabMenuPagerAdapter
        val tabLayout: TabLayout = findViewById(R.id.tabLayout)
        tabLayout.setupWithViewPager(viewPager)

//        viewPager?.addOnPageChangeListener(this)
    }




}
