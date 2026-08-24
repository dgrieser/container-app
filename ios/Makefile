# Everything the iOS side does from a checkout.
#
#   make project    regenerate from app-variants.yaml and write Container.xcodeproj
#   make generate   just the generated files (no Xcode needed)
#   make icons      just the app icons (no Xcode needed)
#   make test       the generator's tests, and the package's if Swift is present
#   make open       generate, then open the project
#
# APP_VERSION overrides the version, exactly as -PappVersion does on the Android
# side:  make project APP_VERSION=v1.2.3

PYTHON ?= python3
PROJECT := Container.xcodeproj
SCHEME ?= container
VERSION_ARG := $(if $(APP_VERSION),--app-version $(APP_VERSION),)

.PHONY: all project generate icons test test-tools test-package open clean

all: project

# The generated files only. Runs anywhere Python and CairoSVG do -- no Mac, no
# Xcode -- which is what lets CI draw the icons on a Linux runner.
generate:
	$(PYTHON) tools/generate.py $(VERSION_ARG)

icons:
	$(PYTHON) tools/generate.py --only-icons $(VERSION_ARG)

# The only step that needs macOS.
project: generate
	@command -v xcodegen >/dev/null 2>&1 || { \
	  echo "xcodegen is not installed: brew install xcodegen"; exit 1; }
	xcodegen generate --spec project.yml --project .
	@echo "Wrote $(PROJECT). Schemes: one per variant in ../app-variants.yaml."

open: project
	open $(PROJECT)

test: test-tools test-package

test-tools:
	cd tools && $(PYTHON) -m unittest discover -s tests -t . -v

# Skipped rather than failed where there is no Swift toolchain, so `make test` is
# still useful on Linux.
test-package:
	@command -v swift >/dev/null 2>&1 \
	  && (cd ContainerKit && swift test) \
	  || echo "swift not found, skipping the package tests"

clean:
	rm -rf Generated $(PROJECT) build .build DerivedData
