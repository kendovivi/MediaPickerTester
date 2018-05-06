package com.kendovivi.mediapicker

import android.support.v4.app.Fragment
import android.support.v4.app.FragmentManager
import android.support.v4.app.FragmentPagerAdapter

class MediaPickerTabMenuPagerAdapter(private val tabMenuList: List<String>, fm: FragmentManager) : FragmentPagerAdapter(fm) {

    override fun getCount(): Int {
       return tabMenuList.size
    }

    override fun getItem(position: Int): Fragment {
        return MediaPickerFragment.newInstance(position)
    }

    override fun getPageTitle(position: Int): CharSequence? {
        return tabMenuList[position]
    }
}