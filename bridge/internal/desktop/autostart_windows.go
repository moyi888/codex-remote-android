//go:build windows

package desktop

import (
	"errors"
	"os"

	"golang.org/x/sys/windows/registry"
)

const (
	autostartRegistryPath = `Software\Microsoft\Windows\CurrentVersion\Run`
	autostartValueName    = "CodexRemote"
	autostartSettingsPath = `Software\CodexRemote`
	autostartConfigured   = "AutostartConfigured"
)

type RegistryAutostartStore struct{}

func (RegistryAutostartStore) Enabled() (bool, error) {
	key, err := registry.OpenKey(registry.CURRENT_USER, autostartRegistryPath, registry.QUERY_VALUE)
	if errors.Is(err, registry.ErrNotExist) {
		return false, nil
	}
	if err != nil {
		return false, err
	}
	defer key.Close()
	value, _, err := key.GetStringValue(autostartValueName)
	if errors.Is(err, registry.ErrNotExist) {
		return false, nil
	}
	if err != nil {
		return false, err
	}
	executable, err := os.Executable()
	if err != nil {
		return false, err
	}
	return autostartValueMatches(value, executable), nil
}

func (RegistryAutostartStore) SetEnabled(enabled bool) error {
	key, _, err := registry.CreateKey(registry.CURRENT_USER, autostartRegistryPath, registry.SET_VALUE)
	if err != nil {
		return err
	}
	defer key.Close()
	if !enabled {
		err := key.DeleteValue(autostartValueName)
		if err != nil && !errors.Is(err, registry.ErrNotExist) {
			return err
		}
		return RegistryAutostartStore{}.MarkConfigured()
	}
	executable, err := os.Executable()
	if err != nil {
		return err
	}
	command, err := autostartCommand(executable)
	if err != nil {
		return err
	}
	if err := key.SetStringValue(autostartValueName, command); err != nil {
		return err
	}
	return RegistryAutostartStore{}.MarkConfigured()
}

func (RegistryAutostartStore) Configured() (bool, error) {
	key, err := registry.OpenKey(registry.CURRENT_USER, autostartSettingsPath, registry.QUERY_VALUE)
	if errors.Is(err, registry.ErrNotExist) {
		return false, nil
	}
	if err != nil {
		return false, err
	}
	defer key.Close()
	value, _, err := key.GetIntegerValue(autostartConfigured)
	if errors.Is(err, registry.ErrNotExist) {
		return false, nil
	}
	return value == 1, err
}

func (RegistryAutostartStore) MarkConfigured() error {
	key, _, err := registry.CreateKey(registry.CURRENT_USER, autostartSettingsPath, registry.SET_VALUE)
	if err != nil {
		return err
	}
	defer key.Close()
	return key.SetDWordValue(autostartConfigured, 1)
}
