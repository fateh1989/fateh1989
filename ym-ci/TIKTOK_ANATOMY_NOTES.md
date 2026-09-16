# TikTok anatomy notes -> YM architecture

This document records clean-room architectural observations from studying a TikTok 46.9.3 APK supplied as a learning sample. It does not copy source code, signing material, secrets, or private protocol implementations.

## 1. Main shell

Observed structural classes include:

- `com.ss.android.ugc.aweme.main.MainActivity`
- `MainFragment`
- `MainPageFragment`
- `MainRootFragment`
- `HomeViewPagerAssem`
- `TopTabAssem`
- `BottomTabAssemBase`
- `MFFeedAssem`

Lesson for YM: the home experience must be the product. Navigation, feed, tabs, and feature modules should be separate components rather than one long settings screen.

## 2. Feed paging

Observed feed/paging layers include:

- `FeedFragment`
- `BaseFeedListFragment`
- `BaseListFragmentPanel`
- `FullFeedFragmentPanel`
- `RecommendFeedFragmentPanel`
- `FollowFeedFragmentPanelMT`
- `VerticalViewPager`
- `FeedItemList`
- separate load-more and refresh abilities

The feed model exposes pagination concepts such as `has_more`, `max_cursor`, and `min_cursor`.

Lesson for YM: separate the vertical pager from feed data. Swiping changes the active item; it must not directly own networking or publishing logic.

## 3. Player lifecycle

Observed player-facing abstractions include:

- `IBaseListFragmentPanel.getPlayerManager`
- `onPreparePlay`
- `onPlaying`
- `onRenderFirstFrame`
- `BaseFeedPlayerView.onPagePause`
- `BaseFeedPlayerView.onScrollEnd`
- `FeedVideoPlayerView`
- `FeedPlayerManagerViewModel`
- `ISimPlayerConfig`

Lesson for YM: player state needs a lifecycle independent of the Activity. Future YM versions should evolve from the current simple player toward a dedicated player engine.

## 4. Preload and cache

Observed preload-related abstractions include:

- `IVideoPreloadConfig`
- `VideoPreloadManager`
- `EnginePreloader`
- `PreloadSessionManager`
- `ISmartFeedPreloadService`

The configuration surface includes concepts such as preload strategy, load-more preload, network speed, buffer thresholds, preconnect, resolution and bitrate policy.

Lesson for YM: next-item preloading belongs in a separate layer. v0.15 starts with a small independent next-media preloader; a later version should use a stronger player/cache engine.

## 5. Comments

Observed comment modules include:

- `CommentService`
- `CommentServiceImpl`
- `CommentHomeViewPagerAssem`
- comment list, input and ViewModel components
- `CommentPreloadRequest`

Lesson for YM: comments are a feature module attached to the feed, not part of the video player. YM's comment wheel should therefore live behind a comment service/state layer.

## 6. Publishing

Observed publishing abstractions include:

- `IAVPublishService`
- `AVPublishServiceImpl`
- `VideoPublishPreviewContainerActivity`
- publish progress handling
- task IDs
- retry/restore concepts
- cover/preview flow

Lesson for YM: the publishing wheel is a task engine. It should keep progress, checkpoints and recovery state separate from the feed UI. YM's existing WorkManager/cloud-plan engine follows this direction.

## YM target architecture

```text
YM Super Feed
├── Feed shell
│   ├── vertical pager
│   ├── side actions
│   ├── top tabs
│   └── bottom navigation
├── Feed data
├── Player engine
├── Preload/cache engine
├── Library
├── Comments service + comment wheel
├── Publish service + publish wheel
├── AUTO scheduler/background engine
├── Activity/history
├── Analytics
└── Account/profile
```

The TikTok sample remains a learning specimen only. YM is implemented with our own code and identity.
