#include "audio8_engine.h"
#include <algorithm>
#include <cmath>
#include <cstring>
#include <fstream>
#include <limits>
#include <sstream>
#include <stdexcept>

namespace audio8 {
namespace {
template<class T> void Write(std::ostream& stream, const T& value) { stream.write(reinterpret_cast<const char*>(&value), sizeof(T)); }
std::ofstream Create(const std::string& path) {
  std::ifstream existing(path, std::ios::binary);
  if (existing.good()) throw std::runtime_error("Output already exists: " + path);
  std::ofstream out(path, std::ios::binary);
  if (!out) throw std::runtime_error("Cannot create: " + path);
  return out;
}
void Header(std::ostream& out, const char* dtype, const std::vector<int64_t>& shape) {
  std::ostringstream s; s << "{'descr': '" << dtype << "', 'fortran_order': False, 'shape': (";
  for (auto dimension: shape) s << dimension << ", ";
  s << "), }";
  std::string h = s.str();
  h.append((64 - ((10 + h.size() + 1) % 64)) % 64, ' '); h.push_back('\n');
  if (h.size() > 65535) throw std::runtime_error("NPY header too long");
  out.write("\x93NUMPY", 6); const uint8_t version[2] = {1, 0}; out.write(reinterpret_cast<const char*>(version), 2);
  uint16_t size = static_cast<uint16_t>(h.size()); Write(out, size); out.write(h.data(), h.size());
}
}
IntArray LoadIntegerNpy(const std::string& path) {
  std::ifstream in(path, std::ios::binary);
  if (!in) throw std::runtime_error("Cannot open NPY: " + path);
  char magic[6]; in.read(magic, 6); if (std::memcmp(magic, "\x93NUMPY", 6)) throw std::runtime_error("Expected NPY");
  uint8_t version[2]; in.read(reinterpret_cast<char*>(version), 2);
  uint32_t header_size = 0;
  if (version[0] == 1) { uint16_t n; in.read(reinterpret_cast<char*>(&n), 2); header_size = n; }
  else if (version[0] == 2) in.read(reinterpret_cast<char*>(&header_size), 4);
  else throw std::runtime_error("Unsupported NPY version");
  std::string header(header_size, '\0'); in.read(header.data(), header.size());
  if (header.find("True") != std::string::npos) throw std::runtime_error("Fortran NPY is unsupported");
  auto start = header.find('('), end = header.find(')', start);
  if (start == std::string::npos || end == std::string::npos) throw std::runtime_error("Missing NPY shape");
  std::string dimensions = header.substr(start + 1, end - start - 1); std::replace(dimensions.begin(), dimensions.end(), ',', ' ');
  std::istringstream shape_stream(dimensions); IntArray result; int64_t d, count = 1;
  while (shape_stream >> d) { if (d < 1) throw std::runtime_error("Invalid NPY dimension"); result.shape.push_back(d); count *= d; }
  if (result.shape.empty()) throw std::runtime_error("NPY scalar is unsupported");
  result.data.resize(static_cast<size_t>(count));
  if (header.find("<i8") != std::string::npos || header.find("|i8") != std::string::npos) {
    in.read(reinterpret_cast<char*>(result.data.data()), count * 8);
  } else if (header.find("<u2") != std::string::npos || header.find("|u2") != std::string::npos) {
    std::vector<uint16_t> raw(static_cast<size_t>(count)); in.read(reinterpret_cast<char*>(raw.data()), count * 2);
    std::copy(raw.begin(), raw.end(), result.data.begin());
  } else throw std::runtime_error("Expected little-endian int64/uint16 NPY");
  if (!in) throw std::runtime_error("Truncated NPY data");
  return result;
}
void SaveInt64Npy(const std::string& path, const std::vector<int64_t>& values, const std::vector<int64_t>& shape) {
  auto out = Create(path); Header(out, "<i8", shape); out.write(reinterpret_cast<const char*>(values.data()), values.size() * sizeof(int64_t));
}
void SaveFloatNpy(const std::string& path, const std::vector<float>& values) {
  auto out = Create(path); Header(out, "<f4", {static_cast<int64_t>(values.size())}); out.write(reinterpret_cast<const char*>(values.data()), values.size() * sizeof(float));
}
void SavePcm16Wav(const std::string& path, const std::vector<float>& pcm, int sample_rate) {
  auto out = Create(path);
  uint32_t bytes = static_cast<uint32_t>(pcm.size() * 2), riff = bytes + 36, fmt = 16, rate = sample_rate, byte_rate = rate * 2;
  uint16_t tag = 1, channels = 1, align = 2, bits = 16;
  out.write("RIFF", 4); Write(out, riff); out.write("WAVEfmt ", 8); Write(out, fmt); Write(out, tag); Write(out, channels);
  Write(out, rate); Write(out, byte_rate); Write(out, align); Write(out, bits); out.write("data", 4); Write(out, bytes);
  for (float sample: pcm) {
    if (!std::isfinite(sample)) throw std::runtime_error("Non-finite PCM");
    int32_t quantized = static_cast<int32_t>(std::lrint(std::clamp(static_cast<double>(sample),-1.0,1.0) * 32768.0));
    int16_t value = static_cast<int16_t>(std::clamp<int32_t>(quantized, -32768, 32767)); Write(out, value);
  }
}
} // namespace audio8
