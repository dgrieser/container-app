# Everything the iOS side does from a checkout.
#
#   make project    regenerate from app-variants.yaml and write Container.xcodeproj
#                   (SKIP_ICONS=1 to skip drawing the icons -- no cairo needed)
#   make generate   just the generated files (no Xcode needed)
#   make icons      just the app icons (no Xcode needed)
#   make test       the generator's tests, and ContainerKit's if Xcode is present
#   make open       generate, then open the project
#
# APP_VERSION overrides the version, exactly as -PappVersion does on the Android
# side:  make project APP_VERSION=v1.2.3

PYTHON ?= python3
PROJECT := Container.xcodeproj
SCHEME ?= container
SIMULATOR ?= platform=iOS Simulator,name=iPhone 15
VERSION_ARG := $(if $(APP_VERSION),--app-version $(APP_VERSION),)

# SKIP_ICONS=1 generates without drawing the app icons, so CairoSVG and the cairo
# library are not needed. Any icons already on disk are left alone, which is how
# CI works: they are rendered on a Linux runner and handed to the Mac.
SKIP_ICONS ?=
ICONS_ARG := $(if $(SKIP_ICONS),--skip-icons,)

.PHONY: all project generate icons test test-tools test-package open clean

all: project

# The generated files only. Runs anywhere Python and CairoSVG do -- no Mac, no
# Xcode -- which is what lets CI draw the icons on a Linux runner.
generate:
	$(PYTHON) tools/generate.py $(VERSION_ARG) $(ICONS_ARG)

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

# ContainerKit is a UIKit and WebKit package, so its tests need a simulator
# rather than `swift test`. Skipped rather than failed without Xcode, so
# `make test` is still useful on Linux.
test-package:
	@command -v xcodebuild >/dev/null 2>&1 || { \
	  echo "xcodebuild not found, skipping the package tests"; exit 0; }; \
	$(MAKE) project && \
	xcodebuild test -project $(PROJECT) -scheme $(SCHEME) \
	  -destination "$(SIMULATOR)"

clean:
	rm -rf Generated $(PROJECT) build .build DerivedData
