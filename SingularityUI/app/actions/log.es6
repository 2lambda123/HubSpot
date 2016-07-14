import Q from 'q';
import Utils from 'utils';

import { fetchTasksForRequest } from './activeTasks';

function __in__(needle, haystack) {
  return haystack.indexOf(needle) >= 0;
}

const fetchData = (taskId, path, offset = undefined, length = 0) => {
  length = Math.max(length, 0);  // API breaks if you request a negative length
  return $.ajax(
    {url: `${ config.apiRoot }/sandbox/${ taskId }/read?${$.param({path, length, offset})}`});
};

const fetchTaskHistory = taskId =>
  $.ajax(
    {url: `${ config.apiRoot }/history/task/${ taskId }`});

export const init = (requestId, taskIdGroups, path, search, viewMode) =>
  ({
    requestId,
    taskIdGroups,
    path,
    search,
    viewMode,
    type: 'LOG_INIT'
  });

export const initTask = (taskId, offset, path, exists) =>
  ({
    taskId,
    offset,
    path,
    exists,
    type: 'LOG_TASK_INIT'
  });

export const taskFileDoesNotExist = (taskGroupId, taskId) =>
  ({
    taskId,
    taskGroupId,
    type: 'LOG_TASK_FILE_DOES_NOT_EXIST'
  });

export const addTaskGroup = (taskIds, search) =>
  ({
    taskIds,
    search,
    type: 'LOG_ADD_TASK_GROUP'
  });

export const finishedLogExists = (taskId) =>
  ({
    taskId,
    type: 'LOG_FINISHED_LOG_EXISTS'
  });

export const taskGroupReady = taskGroupId =>
  ({
    taskGroupId,
    type: 'LOG_TASK_GROUP_READY'
  });

export const taskHistory = (taskGroupId, taskId, theTaskHistory) =>
  ({
    taskGroupId,
    taskId,
    taskHistory: theTaskHistory,
    type: 'LOG_TASK_HISTORY'
  });

export const getTasks = (taskGroup, tasks) => taskGroup.taskIds.map(taskId => tasks[taskId]);

export const doesFinishedLogExist = (taskIds) =>
  (dispatch) => {
    taskIds.map((taskId) => {
      const actualPath = config.finishedTaskLogPath.replace('$TASK_ID', taskId);
      return fetchData(taskId, actualPath)
      .done(() => dispatch(finishedLogExists(taskId)));
    });
  };

export const taskFilesize = (taskId, filesize) =>
  ({
    taskId,
    filesize,
    type: 'LOG_TASK_FILESIZE'
  });

export const updateFilesizes = () =>
  (dispatch, getState) => {
    const tasks = getState();
    for (const taskId of tasks) {
      fetchData(taskId, tasks[taskId.path]).done(({offset}) => {
        dispatch(taskFilesize(taskId, offset));
      });
    }
  };


export const taskData = (taskGroupId, taskId, data, offset, nextOffset, append, maxLines) =>
  ({
    taskGroupId,
    taskId,
    data,
    offset,
    nextOffset,
    append,
    maxLines,
    type: 'LOG_TASK_DATA'
  });

export const taskGroupFetchNext = taskGroupId =>
  (dispatch, getState) => {
    const state = getState();
    const {taskGroups, logRequestLength, maxLines} = state;

    const taskGroup = taskGroups[taskGroupId];
    const tasks = getTasks(taskGroup, state.tasks);

    // bail early if there's already a pending request
    if (taskGroup.pendingRequests) {
      return Promise.resolve();
    }

    dispatch({taskGroupId, type: 'LOG_REQUEST_START'});
    const promises = tasks.map(({taskId, exists, maxOffset, path, initialDataLoaded}) => {
      if (initialDataLoaded && exists !== false) {
        const xhr = fetchData(taskId, path, maxOffset, logRequestLength);
        const promise = xhr.done(({data, offset, nextOffset}) => {
          if (data.length > 0) {
            nextOffset = offset + data.length;
            return dispatch(taskData(taskGroupId, taskId, data, offset, nextOffset, true, maxLines));
          }
          return Promise.resolve();
        }).error(error => Utils.ignore404(error));
        promise.taskId = taskId;
        return promise;
      }
      return Promise.resolve();
    });

    return Promise.all(promises).then(() => dispatch({taskGroupId, type: 'LOG_REQUEST_END'})).catch((error) => {
      if (error.status === 404) {
        dispatch(taskFileDoesNotExist(taskGroupId, error.taskId));
      }
    });
  };

export const taskGroupFetchPrevious = taskGroupId =>
  (dispatch, getState) => {
    const state = getState();
    const {taskGroups, logRequestLength, maxLines} = state;

    const taskGroup = taskGroups[taskGroupId];
    const tasks = getTasks(taskGroup, state.tasks);

    // bail early if all tasks are at the top
    if (_.all(tasks.map(({minOffset}) => minOffset === 0))) {
      return Promise.resolve();
    }

    // bail early if there's already a pending request
    if (taskGroup.pendingRequests) {
      return Promise.resolve();
    }

    dispatch({taskGroupId, type: 'LOG_REQUEST_START'});
    const promises = tasks.map(({taskId, exists, minOffset, path, initialDataLoaded}) => {
      if (minOffset > 0 && initialDataLoaded && exists !== false) {
        const xhr = fetchData(taskId, path, Math.max(minOffset - logRequestLength, 0), Math.min(logRequestLength, minOffset));
        return xhr.done(({data, offset, nextOffset}) => {
          if (data.length > 0) {
            nextOffset = offset + data.length;
            return dispatch(taskData(taskGroupId, taskId, data, offset, nextOffset, false, maxLines));
          }
          return Promise.resolve();
        });
      }
      return Promise.resolve();
    });

    return Promise.all(promises).then(() => dispatch({taskGroupId, type: 'LOG_REQUEST_END'}));
  };

export const updateGroups = () =>
  (dispatch, getState) =>
    getState().taskGroups.map((taskGroup, taskGroupId) => {
      if (!taskGroup.pendingRequests) {
        if (taskGroup.top) {
          return dispatch(taskGroupFetchPrevious(taskGroupId));
        }
        if (taskGroup.bottom || taskGroup.tailing) {
          return dispatch(taskGroupFetchNext(taskGroupId));
        }
        return null;
      }
      return null;
    });

export const updateTaskStatus = (taskGroupId, taskId) =>
  (dispatch) =>
    fetchTaskHistory(taskId, ['taskUpdates']).done(data => dispatch(taskHistory(taskGroupId, taskId, data)));

export const updateTaskStatuses = () =>
  (dispatch, getState) => {
    const {tasks, taskGroups} = getState();
    return taskGroups.map((taskGroup, taskGroupId) =>
      getTasks(taskGroup, tasks).map(({taskId, terminated}) => {
        if (terminated) {
          return Promise.resolve();
        }
        return dispatch(updateTaskStatus(taskGroupId, taskId));
      })
    );
  };

export const taskGroupTop = (taskGroupId, visible) =>
  (dispatch, getState) => {
    if (getState().taskGroups[taskGroupId].top !== visible) {
      dispatch({taskGroupId, visible, type: 'LOG_TASK_GROUP_TOP'});
      if (visible) {
        return dispatch(taskGroupFetchPrevious(taskGroupId));
      }
    }
    return null;
  };

export const taskGroupBottom = (taskGroupId, visible, tailing = false) =>
  (dispatch, getState) => {
    const { taskGroups, tasks } = getState();
    const taskGroup = taskGroups[taskGroupId];
    if (taskGroup.tailing !== tailing) {
      if (tailing === false || _.all(getTasks(taskGroup, tasks).map(({maxOffset, filesize}) => maxOffset >= filesize))) {
        return dispatch({taskGroupId, tailing, type: 'LOG_TASK_GROUP_TAILING'});
      }
    }
    if (taskGroup.bottom !== visible) {
      dispatch({taskGroupId, visible, type: 'LOG_TASK_GROUP_BOTTOM'});
      if (visible) {
        return dispatch(taskGroupFetchNext(taskGroupId));
      }
    }
    return null;
  };

export const clickPermalink = offset =>
  ({
    offset,
    type: 'LOG_CLICK_OFFSET_LINK'
  });

export const selectLogColor = color =>
  ({
    color,
    type: 'LOG_SELECT_COLOR'
  });

export const initialize = (requestId, path, search, taskIds, viewMode) =>
  (dispatch) => {
    let taskIdGroups;
    if (viewMode === 'unified') {
      taskIdGroups = [taskIds];
    } else {
      taskIdGroups = taskIds.map(taskId => [taskId]);
    }

    dispatch(init(requestId, taskIdGroups, path, search, viewMode));

    const groupPromises = taskIdGroups.map((taskIdsInGroup, taskGroupId) => {
      const taskInitPromises = taskIdsInGroup.map((taskId) => {
        const taskInitDeferred = Q.defer();
        const resolvedPath = path.replace('$TASK_ID', taskId);
        fetchData(taskId, resolvedPath).done(({offset}) => {
          dispatch(initTask(taskId, offset, resolvedPath, true));
          return taskInitDeferred.resolve();
        })
        .error(({status}) => {
          if (status === 404) {
            dispatch(taskFileDoesNotExist(taskGroupId, taskId));
            return taskInitDeferred.resolve();
          }
          return taskInitDeferred.reject();
        });
        return taskInitDeferred.promise;
      });

      const taskStatusPromises = taskIdsInGroup.map(taskId => dispatch(updateTaskStatus(taskGroupId, taskId)));

      return Promise.all(taskInitPromises, taskStatusPromises).then(() =>
        dispatch(taskGroupFetchPrevious(taskGroupId)).then(() => dispatch(taskGroupReady(taskGroupId)))
      );
    });

    return Promise.all(groupPromises);
  };

export const initializeUsingActiveTasks = (requestId, path, search, viewMode) =>
  (dispatch) => {
    const deferred = Q.defer();
    fetchTasksForRequest(requestId).done((tasks) => {
      const taskIds = _.sortBy(_.pluck(tasks, 'taskId'), taskId => taskId.instanceNo).map(taskId => taskId.id);
      return dispatch(initialize(requestId, path, search, taskIds, viewMode)).then(() => deferred.resolve());
    });
    return deferred.promise;
  };

export const switchViewMode = newViewMode =>
  (dispatch, getState) => {
    const { taskGroups, path, activeRequest, search, viewMode } = getState();

    if (__in__(newViewMode, ['custom', viewMode])) {
      return null;
    }

    const taskIds = _.flatten(_.pluck(taskGroups, 'taskIds'));

    dispatch({viewMode: newViewMode, type: 'LOG_SWITCH_VIEW_MODE'});
    return dispatch(initialize(activeRequest.requestId, path, search, taskIds, newViewMode));
  }
;

export const setCurrentSearch = newSearch =>  // TODO: can we do something less heavyweight?
  (dispatch, getState) => {
    const {activeRequest, path, taskGroups, currentSearch, viewMode} = getState();
    if (newSearch !== currentSearch) {
      return dispatch(initialize(activeRequest.requestId, path, newSearch, _.flatten(_.pluck(taskGroups, 'taskIds')), viewMode));
    }
    return null;
  };

export const toggleTaskLog = taskId =>
  (dispatch, getState) => {
    const {search, path, tasks, viewMode} = getState();
    if (taskId in tasks) {
      // only remove task if it's not the last one
      if (Object.keys(tasks).length > 1) {
        return dispatch({taskId, type: 'LOG_REMOVE_TASK'});
      }
      return null;
    }
    if (viewMode === 'split') {
      dispatch(addTaskGroup([taskId], search));
    }

    const resolvedPath = path.replace('$TASK_ID', taskId);

    return fetchData(taskId, resolvedPath).done(({offset}) => {
      dispatch(initTask(taskId, offset, resolvedPath, true));

      return getState().taskGroups.map((taskGroup, taskGroupId) => {
        if (__in__(taskId, taskGroup.taskIds)) {
          dispatch(updateTaskStatus(taskGroupId, taskId));
          return dispatch(taskGroupFetchPrevious(taskGroupId)).then(() => dispatch(taskGroupReady(taskGroupId)));
        }
        return null;
      });
    });
  };

export const removeTaskGroup = taskGroupId =>
  (dispatch, getState) => {
    const { taskIds } = getState().taskGroups[taskGroupId];
    return dispatch({taskGroupId, taskIds, type: 'LOG_REMOVE_TASK_GROUP'});
  };

export const expandTaskGroup = taskGroupId =>
  (dispatch, getState) => {
    const { taskIds } = getState().taskGroups[taskGroupId];
    return dispatch({taskGroupId, taskIds, type: 'LOG_EXPAND_TASK_GROUP'});
  };

export const scrollToTop = taskGroupId =>
  (dispatch, getState) => {
    const { taskIds } = getState().taskGroups[taskGroupId];
    dispatch({taskGroupId, taskIds, type: 'LOG_SCROLL_TO_TOP'});
    return dispatch(taskGroupFetchNext(taskGroupId));
  };

export const scrollAllToTop = () =>
  (dispatch, getState) => {
    dispatch({type: 'LOG_SCROLL_ALL_TO_TOP'});
    return getState().taskGroups.map((taskGroup, taskGroupId) => dispatch(taskGroupFetchNext(taskGroupId)));
  };

export const scrollToBottom = taskGroupId =>
  (dispatch, getState) => {
    const { taskIds } = getState().taskGroups[taskGroupId];
    dispatch({taskGroupId, taskIds, type: 'LOG_SCROLL_TO_BOTTOM'});
    return dispatch(taskGroupFetchPrevious(taskGroupId));
  };

export const scrollAllToBottom = () =>
  (dispatch, getState) => {
    dispatch({type: 'LOG_SCROLL_ALL_TO_BOTTOM'});
    return getState().taskGroups.map((taskGroup, taskGroupId) => dispatch(taskGroupFetchPrevious(taskGroupId)));
  };

export default { initialize, initializeUsingActiveTasks, taskGroupFetchNext, taskGroupFetchPrevious, clickPermalink, updateGroups, updateTaskStatuses, updateFilesizes, taskGroupTop, taskGroupBottom, selectLogColor, switchViewMode, setCurrentSearch, toggleTaskLog, scrollToTop, scrollAllToTop, scrollToBottom, scrollAllToBottom, removeTaskGroup, expandTaskGroup };
