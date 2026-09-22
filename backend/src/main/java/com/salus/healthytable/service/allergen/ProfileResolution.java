package com.salus.healthytable.service.allergen;

/** 소비자는 Complete/Partial을 먼저 분기해야 한다. 공통 resolved() accessor를 두지 않는다. */
public sealed interface ProfileResolution permits CompleteProfileResolution, PartialProfileResolution {
}
