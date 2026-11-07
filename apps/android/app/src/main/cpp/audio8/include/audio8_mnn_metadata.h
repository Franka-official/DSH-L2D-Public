#pragma once
#ifdef __cplusplus
extern "C" {
#endif
// Creates only runtime/backend objects for device metadata. No model/session,
// tensors, target sampling or model inference is created or executed.
const char* audio8_mnn_runtime_info_json(void);
#ifdef __cplusplus
}
#endif
