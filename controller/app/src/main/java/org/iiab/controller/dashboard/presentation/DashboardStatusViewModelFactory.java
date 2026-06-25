/*
 * ============================================================================
 * Name        : DashboardStatusViewModelFactory.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Hand-wired DI for DashboardStatusViewModel (Data -> Domain ->
 *               Presentation), same approach as RootfsViewModelFactory.
 * ============================================================================
 */
package org.iiab.controller.dashboard.presentation;

import androidx.annotation.NonNull;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import org.iiab.controller.dashboard.data.HttpServerReachability;

public class DashboardStatusViewModelFactory implements ViewModelProvider.Factory {

    @NonNull
    @Override
    @SuppressWarnings("unchecked")
    public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
        if (modelClass.isAssignableFrom(DashboardStatusViewModel.class)) {
            return (T) new DashboardStatusViewModel(new HttpServerReachability());
        }
        throw new IllegalArgumentException("Unknown ViewModel class: " + modelClass.getName());
    }
}
