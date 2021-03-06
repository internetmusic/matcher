package com.wavesplatform.dex

sealed abstract class ApplicationStopReason(val code: Int)
case object StartingMatcherError            extends ApplicationStopReason(10)
case object RecoveryError                   extends ApplicationStopReason(12)
case object EventProcessingError            extends ApplicationStopReason(16)
case object UnsynchronizedNodeError         extends ApplicationStopReason(18)
case object MatcherStateCheckingFailedError extends ApplicationStopReason(20)
