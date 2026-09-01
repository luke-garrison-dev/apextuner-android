package com.apextuner.feature.tools.advanced;

interface IPrivilegedUserService {
    void destroy() = 16777114;
    String executeReadOnly(int commandId) = 1;
    String setAnimationScales(float windowScale, float transitionScale, float animatorScale) = 2;
    String setApplicationEnabledState(String packageName, int state) = 3;
    String forceStopPackage(String packageName) = 4;
    String readCpuPolicy(int policyId) = 5;
    String writeCpuPolicy(int policyId, String governor, long minimumKHz, long maximumKHz) = 6;
}
