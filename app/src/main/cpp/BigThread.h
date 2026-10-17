/*
    Clamshell DS - threads with a desktop-sized stack.
    Android's default native thread stack is ~1 MB; melonDS is developed
    against 8 MB desktop stacks, so give emulator threads the same headroom.
    Licensed under the GNU GPL v3 or later.
*/

#pragma once

#include <pthread.h>

#include <functional>
#include <memory>

namespace clamshell
{

class BigThread
{
public:
    static constexpr size_t kDefaultStack = 8 * 1024 * 1024;

    BigThread() = default;
    BigThread(const BigThread&) = delete;
    BigThread& operator=(const BigThread&) = delete;
    ~BigThread() { Detach(); }

    bool Start(std::function<void()> fn, size_t stackSize = kDefaultStack)
    {
        auto* boxed = new std::function<void()>(std::move(fn));
        pthread_attr_t attr;
        pthread_attr_init(&attr);
        pthread_attr_setstacksize(&attr, stackSize);
        int err = pthread_create(&handle, &attr, &Entry, boxed);
        pthread_attr_destroy(&attr);
        if (err != 0)
            err = pthread_create(&handle, nullptr, &Entry, boxed); // retry with the default stack
        if (err != 0)
        {
            delete boxed;
            return false;
        }
        started = true;
        return true;
    }

    void Join()
    {
        if (!started) return;
        pthread_join(handle, nullptr);
        started = false;
    }

    void Detach()
    {
        if (!started) return;
        pthread_detach(handle);
        started = false;
    }

    bool Joinable() const { return started; }
    bool IsCurrent() const { return started && pthread_equal(pthread_self(), handle); }

private:
    static void* Entry(void* arg)
    {
        std::unique_ptr<std::function<void()>> fn(static_cast<std::function<void()>*>(arg));
        (*fn)();
        return nullptr;
    }

    pthread_t handle {};
    bool started = false;
};

}
