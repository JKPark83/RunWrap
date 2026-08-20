package com.jkpark.runwrap

import android.app.Application

/// 앱 전역 진입점 — M0에서는 비어 있다.
/// 이후 앱 스코프 객체(HC 클라이언트 공유 등)가 필요해지면 여기에 둔다.
class RunWrapApp : Application()
