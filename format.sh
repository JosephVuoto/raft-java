find . -name "*.java" | xargs clang-format -style=file -i -fallback-style=none
